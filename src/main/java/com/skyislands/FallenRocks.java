package com.skyislands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Fallen rocks (structures sky_islands:fallen_rock_1..4, tag sky_islands:fallen_rocks): small piles of deepslate that lie on
 * the ground. Stand on one for a second and it is recalled, like the fallen sky island rocks in Tears of the Kingdom: the
 * whole pile (real blocks) rises with you up to Y=430, hangs there for a moment so you can run off and glide to a nearby
 * island, then falls back down and becomes the same pile of rocks again, ready to be used again.
 *
 * <ul>
 * <li>A rock only works while it is whole: if even one of its blocks is gone (or one is added) it stays an ordinary pile for
 * good. This is judged from the blocks themselves, so a rock that was broken and put back together exactly looks whole again
 * after the server restarts.</li>
 * <li>A rock needs a clear way up (no block with collision, and no fluid, above it): a rock that would be generated with
 * something above it is removed again right after its chunk is generated, and a rock that gets covered later does not
 * work while it is covered.</li>
 * </ul>
 *
 * <p>The lift itself: the rock follows the rider's height block by block while the rider is held up by a Levitation effect
 * whose strength is eased down towards the top, so there is no hard stop (and no fall damage).
 */
public final class FallenRocks {
	private static final TagKey<Structure> ROCKS = TagKey.create(Registries.STRUCTURE, SkyIslands.id("fallen_rocks"));

	/** The rock rises until the player standing on it is at this height. */
	private static final int TARGET_Y = 430;
	/** How long a player has to stand on a rock before it is recalled. */
	private static final int STAND_TICKS = 20;
	/** How long the rock stays at the top (8 seconds). */
	private static final int HOVER_TICKS = 160;
	/** After a rock has come down it cannot be recalled again for this long. */
	private static final int COOLDOWN_TICKS = 100;
	/** Top speed of the rise, in blocks per tick (1.3 is 26 blocks per second). */
	private static final double MAX_RISE_SPEED = 1.3;
	/** A rise that has not finished after this many ticks is finished at once. */
	private static final int MAX_RISE_TICKS = 1200;
	/** Blocks above the top of the rise that are checked for obstructions too. */
	private static final int CLEARANCE_ABOVE_TARGET = 4;
	/** Setting blocks without neighbour shape updates (2 = send to clients, 16 = no shape update), so stairs keep their shape. */
	private static final int SET_FLAGS = 2 | 16;
	/** The fall of the rock speeds up by this much per tick, to at most FALL_MAX_SPEED blocks per tick. */
	private static final double FALL_ACCELERATION = 0.2;
	private static final double FALL_MAX_SPEED = 4.0;
	/** A rock is dropped to the ground at once when no player is within this many blocks (its chunks could unload). */
	private static final double KEEP_ALIVE_RANGE = 160.0;
	/** Chunks checked per tick after generation, and how many times a chunk is tried again while its neighbours are missing. */
	private static final int GEN_PER_TICK = 4;
	private static final int GEN_MAX_TRIES = 200;

	private enum Phase { RISING, HOVER, FALLING }

	/** One block of a rock, relative to the minimum corner of its bounding box. */
	private record Cell(int x, int y, int z, BlockState state) { }

	private static final class Rock {
		final ServerLevel level;
		final BoundingBox box;
		final long key;
		final List<Cell> cells;

		UUID driver;
		double startFeet;
		int total;
		/** How far up the rock is from where it was generated. */
		int dy;
		Phase phase = Phase.RISING;
		int age;
		int timer;
		int lastAmp = -1;
		double fallDistance;
		double fallSpeed;

		Rock(ServerLevel level, BoundingBox box, long key, List<Cell> cells) {
			this.level = level;
			this.box = box;
			this.key = key;
			this.cells = cells;
		}
	}

	/** What a player standing on a rock has to wait for (rock is null when this rock does not work). */
	private static final class Pending {
		final long key;
		final Rock rock;
		int ticks;

		Pending(long key, Rock rock) {
			this.key = key;
			this.rock = rock;
		}
	}

	private static final class Generated {
		final ServerLevel level;
		final ChunkPos pos;
		final int tries;

		Generated(ServerLevel level, ChunkPos pos, int tries) {
			this.level = level;
			this.pos = pos;
			this.tries = tries;
		}
	}

	private static final Map<Long, Rock> ACTIVE = new HashMap<>();
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	/** Rocks that were seen with a block missing or added (until the server stops). */
	private static final Set<Long> DEAD = new HashSet<>();
	private static final Map<Long, Integer> COOLDOWN = new HashMap<>();
	/** Chunks that have just been generated. As in GroundFreezer, the event itself must not touch the level. */
	private static final Queue<Generated> QUEUE = new ConcurrentLinkedQueue<>();
	private static boolean disabled;
	private static boolean unknownShapeLogged;

	private FallenRocks() { }

	public static void register() {
		ServerChunkEvents.CHUNK_GENERATE.register(FallenRocks::onGenerate);
		ServerTickEvents.END_SERVER_TICK.register(FallenRocks::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(FallenRocks::restoreAll);
	}

	public static void clear() {
		ACTIVE.clear();
		PENDING.clear();
		DEAD.clear();
		COOLDOWN.clear();
		QUEUE.clear();
	}

	private static void onGenerate(ServerLevel level, LevelChunk chunk) {
		if (!disabled && Level.OVERWORLD.equals(level.dimension())) {
			QUEUE.add(new Generated(level, chunk.getPos(), 0));
		}
	}

	/** Server stopping: every rock that is up in the air is put back on the ground, so none is saved floating. */
	private static void restoreAll(MinecraftServer server) {
		for (Rock rock : ACTIVE.values()) {
			try {
				shift(rock, 0);
			} catch (Throwable t) {
				SkyIslands.LOGGER.error("Sky Islands: could not put a fallen rock back", t);
			}
		}

		ACTIVE.clear();
	}

	private static void tick(MinecraftServer server) {
		if (disabled) {
			return;
		}

		try {
			int now = server.getTickCount();
			processGenerated();
			advanceRocks(now);
			scanPlayers(server, now);
		} catch (Throwable t) {
			disabled = true;
			SkyIslands.LOGGER.error("Sky Islands fallen rocks failed and were disabled for this session", t);
			restoreAll(server);
			QUEUE.clear();
		}
	}

	// ---------------------------------------------------------------- generation: no rock under anything

	private static void processGenerated() {
		for (int i = 0; i < GEN_PER_TICK; i++) {
			Generated next = QUEUE.poll();

			if (next == null) {
				return;
			}

			ServerLevel level = next.level;
			BlockPos corner = new BlockPos(next.pos.getMinBlockX(), 64, next.pos.getMinBlockZ());

			if (!level.hasChunkAt(corner)) {
				if (next.tries < GEN_MAX_TRIES) {
					QUEUE.add(new Generated(level, next.pos, next.tries + 1));
				}

				continue;
			}

			boolean again = false;
			LevelChunk chunk = level.getChunkAt(corner);

			for (StructureStart start : chunk.getAllStarts().values()) {
				if (!start.isValid()) {
					continue;
				}

				BoundingBox box = start.getBoundingBox();
				BlockPos center = new BlockPos((box.minX() + box.maxX()) / 2, (box.minY() + box.maxY()) / 2, (box.minZ() + box.maxZ()) / 2);
				StructureStart found = level.structureManager().getStructureWithPieceAt(center, ROCKS);

				if (!found.isValid() || !sameBox(found.getBoundingBox(), box)) {
					continue;
				}

				if (!columnsLoaded(level, box)) {
					again = true;
					continue;
				}

				if (obstructed(level, box)) {
					removeRock(level, box);
				}
			}

			if (again && next.tries < GEN_MAX_TRIES) {
				QUEUE.add(new Generated(level, next.pos, next.tries + 1));
			}
		}
	}

	private static boolean sameBox(BoundingBox a, BoundingBox b) {
		return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
				&& a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
	}

	private static boolean columnsLoaded(ServerLevel level, BoundingBox box) {
		return level.hasChunkAt(new BlockPos(box.minX(), 64, box.minZ())) && level.hasChunkAt(new BlockPos(box.maxX(), 64, box.minZ()))
				&& level.hasChunkAt(new BlockPos(box.minX(), 64, box.maxZ())) && level.hasChunkAt(new BlockPos(box.maxX(), 64, box.maxZ()));
	}

	/** Any block with collision, or any fluid, above the rock on the way up (to just above the top of the rise). */
	private static boolean obstructed(ServerLevel level, BoundingBox box) {
		int height = box.maxY() - box.minY() + 1;
		int top = TARGET_Y + height + CLEARANCE_ABOVE_TARGET;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				for (int y = box.maxY() + 1; y <= top; y++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);

					if (state.isAir()) {
						continue;
					}

					if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, pos).isEmpty()) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/** A rock that cannot be recalled is not generated at all: its blocks are taken out again. */
	private static void removeRock(ServerLevel level, BoundingBox box) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					pos.set(x, y, z);

					if (isRockBlock(level.getBlockState(pos))) {
						level.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState(), 2);
					}
				}
			}
		}
	}

	// ---------------------------------------------------------------- standing on a rock

	private static boolean isRockBlock(BlockState state) {
		return state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.CHISELED_TUFF)
				|| state.is(Blocks.COBBLED_DEEPSLATE_STAIRS) || state.is(Blocks.COBBLED_DEEPSLATE_SLAB);
	}

	/** How many blocks of the rock palette each rock has when whole, from its size (the four rocks all differ in size). */
	private static int expectedBlocks(BoundingBox box) {
		int width = box.maxX() - box.minX() + 1;
		int depth = box.maxZ() - box.minZ() + 1;
		int height = box.maxY() - box.minY() + 1;
		int lo = Math.min(width, depth);
		int hi = Math.max(width, depth);

		if (height == 2 && lo == 2 && hi == 3) {
			return 8;
		} else if (height == 3 && lo == 3 && hi == 3) {
			return 19;
		} else if (height == 4 && lo == 2 && hi == 3) {
			return 14;
		} else if (height == 2 && lo == 2 && hi == 2) {
			return 6;
		}

		return -1;
	}

	/** The rock if it can be recalled right now (whole, with a clear way up), otherwise null. */
	private static Rock evaluate(ServerLevel level, BoundingBox box, long key) {
		if (DEAD.contains(key) || box.minY() >= TARGET_Y - 8) {
			return null;
		}

		int expected = expectedBlocks(box);

		if (expected < 0) {
			if (!unknownShapeLogged) {
				unknownShapeLogged = true;
				SkyIslands.LOGGER.warn("Sky Islands: a fallen rock has an unexpected size {}x{}x{} and will not work",
						box.maxX() - box.minX() + 1, box.maxY() - box.minY() + 1, box.maxZ() - box.minZ() + 1);
			}

			return null;
		}

		List<Cell> cells = new ArrayList<>();
		Set<Long> taken = new HashSet<>();
		int rockBlocks = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		// the rock's own blocks (only these: the ground around and under it is never carried)
		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);

					if (isRockBlock(state)) {
						rockBlocks++;
						cells.add(new Cell(x - box.minX(), y - box.minY(), z - box.minZ(), state));
						taken.add(pos.asLong());
					}
				}
			}
		}

		// whatever lies loose on top of it (a layer of snow, grass, a flower) goes up with it instead of being left in the air
		int rockCells = cells.size();

		for (int i = 0; i < rockCells; i++) {
			Cell cell = cells.get(i);
			pos.set(box.minX() + cell.x(), box.minY() + cell.y() + 1, box.minZ() + cell.z());

			if (taken.contains(pos.asLong())) {
				continue;
			}

			BlockState above = level.getBlockState(pos);

			if (above.isAir() || !above.getFluidState().isEmpty() || !above.getCollisionShape(level, pos).isEmpty()) {
				continue;
			}

			if (above.hasBlockEntity()) {
				return null;
			}

			cells.add(new Cell(cell.x(), cell.y() + 1, cell.z(), above));
			taken.add(pos.asLong());
		}

		if (rockBlocks != expected) {
			DEAD.add(key);
			return null;
		}

		if (obstructed(level, box)) {
			return null;
		}

		return new Rock(level, box, key, cells);
	}

	private static void scanPlayers(MinecraftServer server, int now) {
		Set<UUID> onRock = new HashSet<>();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player.level() instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())
					|| player.isSpectator() || !player.isAlive() || !player.onGround() || player.getY() > TARGET_Y - 20) {
				continue;
			}

			BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.01, player.getZ());

			if (!isRockBlock(level.getBlockState(below))) {
				continue;
			}

			StructureStart start = level.structureManager().getStructureWithPieceAt(below, ROCKS);

			if (!start.isValid()) {
				continue;
			}

			BoundingBox box = start.getBoundingBox();
			long key = BlockPos.asLong(box.minX(), box.minY(), box.minZ());

			if (ACTIVE.containsKey(key) || COOLDOWN.getOrDefault(key, 0) > now) {
				continue;
			}

			UUID id = player.getUUID();
			onRock.add(id);
			Pending pending = PENDING.get(id);

			if (pending == null || pending.key != key) {
				pending = new Pending(key, evaluate(level, box, key));
				PENDING.put(id, pending);
			}

			if (pending.rock == null) {
				continue;
			}

			pending.ticks++;

			if (pending.ticks == 1 || pending.ticks == 8 || pending.ticks == 15) {
				command(level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.hit block @a %.1f %.1f %.1f 1.5 0.5",
						player.getX(), player.getY(), player.getZ()));
				command(level, String.format(Locale.ROOT, "particle minecraft:poof %.2f %.2f %.2f 0.6 0.1 0.6 0.01 6",
						player.getX(), player.getY(), player.getZ()));
			}

			if (pending.ticks >= STAND_TICKS) {
				PENDING.remove(id);
				start(level, player, box, key);
			}
		}

		PENDING.keySet().retainAll(onRock);
	}

	private static void start(ServerLevel level, ServerPlayer player, BoundingBox box, long key) {
		// looked at again: the rock may have changed during the second the player stood there
		Rock rock = evaluate(level, box, key);

		if (rock == null) {
			return;
		}

		rock.driver = player.getUUID();
		rock.startFeet = player.getY();
		rock.total = TARGET_Y - (int) Math.floor(rock.startFeet + 1.0E-4);

		if (rock.total < 10) {
			return;
		}

		ACTIVE.put(key, rock);
		command(level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.break block @a %.1f %.1f %.1f 3.0 0.5",
				player.getX(), player.getY(), player.getZ()));
		command(level, String.format(Locale.ROOT, "playsound minecraft:entity.wind_charge.wind_burst block @a %.1f %.1f %.1f 2.0 0.6",
				player.getX(), player.getY(), player.getZ()));
	}

	// ---------------------------------------------------------------- the rock in motion

	private static void advanceRocks(int now) {
		Iterator<Rock> it = ACTIVE.values().iterator();

		while (it.hasNext()) {
			Rock rock = it.next();
			boolean keep;

			try {
				keep = advance(rock, now);
			} catch (Throwable t) {
				SkyIslands.LOGGER.error("Sky Islands: a fallen rock failed and was put back on the ground", t);
				keep = false;
			}

			if (!keep) {
				if (rock.dy != 0) {
					shift(rock, 0);
				}

				COOLDOWN.put(rock.key, now + COOLDOWN_TICKS);
				it.remove();
			}
		}
	}

	/** Returns false when the rock is back on the ground and finished. */
	private static boolean advance(Rock rock, int now) {
		rock.age++;
		double cx = (rock.box.minX() + rock.box.maxX() + 1) / 2.0;
		double cz = (rock.box.minZ() + rock.box.maxZ() + 1) / 2.0;
		boolean watched = false;

		for (ServerPlayer player : rock.level.players()) {
			double dx = player.getX() - cx;
			double dz = player.getZ() - cz;

			if (dx * dx + dz * dz < KEEP_ALIVE_RANGE * KEEP_ALIVE_RANGE) {
				watched = true;
				break;
			}
		}

		if (!watched) {
			return false;
		}

		switch (rock.phase) {
			case RISING -> rise(rock, cx, cz);
			case HOVER -> {
				rock.timer++;

				if (rock.timer >= HOVER_TICKS - 40 && rock.timer % 10 == 0) {
					command(rock.level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.hit block @a %.1f %.1f %.1f 3.0 0.6",
							cx, rock.box.minY() + rock.dy, cz));
				}

				if (rock.timer >= HOVER_TICKS) {
					rock.phase = Phase.FALLING;
					rock.fallDistance = 0.0;
					rock.fallSpeed = 0.0;
				}
			}
			case FALLING -> {
				rock.fallSpeed = Math.min(FALL_MAX_SPEED, rock.fallSpeed + FALL_ACCELERATION);
				rock.fallDistance += rock.fallSpeed;
				int newDy = Math.max(0, rock.total - (int) Math.floor(rock.fallDistance));

				if (newDy != rock.dy) {
					shift(rock, newDy);
				}

				if (rock.dy <= 0) {
					double y = rock.box.minY() + 0.5;
					command(rock.level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.break block @a %.1f %.1f %.1f 4.0 0.6", cx, y, cz));
					command(rock.level, String.format(Locale.ROOT, "particle minecraft:poof %.2f %.2f %.2f 1.0 0.3 1.0 0.05 30", cx, y, cz));
					return false;
				}
			}
		}

		return true;
	}

	private static void rise(Rock rock, double cx, double cz) {
		List<ServerPlayer> carried = carried(rock);
		ServerPlayer driver = null;

		for (ServerPlayer player : carried) {
			if (player.getUUID().equals(rock.driver)) {
				driver = player;
			}
		}

		// the rock follows the rider (never above their feet); with no rider it keeps going at its own pace
		double risen = driver != null ? driver.getY() - rock.startFeet : rock.dy + 1.0;
		double remaining = rock.total - risen;

		if (remaining <= 0.0 || rock.age > MAX_RISE_TICKS) {
			shift(rock, rock.total);

			for (ServerPlayer player : carried) {
				UUID id = player.getUUID();
				command(rock.level, "effect clear " + id + " minecraft:levitation");
				// the rider has a little upward speed left when the lift lets go: no fall damage from it
				command(rock.level, "effect give " + id + " minecraft:slow_falling 3 0 true");
			}

			command(rock.level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.break block @a %.1f %.1f %.1f 3.0 0.8",
					cx, rock.box.minY() + rock.dy, cz));
			rock.phase = Phase.HOVER;
			rock.timer = 0;
			return;
		}

		double speed = Math.min(MAX_RISE_SPEED, 0.3 + 0.12 * remaining);
		int want = driver != null ? (int) Math.floor(risen) : rock.dy + Math.max(1, (int) Math.round(speed));
		want = Math.max(rock.dy, Math.min(rock.total, want));

		if (want != rock.dy) {
			shift(rock, want);
		}

		// Levitation rises towards 0.05 blocks per tick per level
		int amplifier = Math.max(0, (int) Math.round(speed / 0.05) - 1);

		for (ServerPlayer player : carried) {
			UUID id = player.getUUID();

			if (amplifier != rock.lastAmp) {
				// a weaker effect would not replace a stronger one that is still running
				command(rock.level, "effect clear " + id + " minecraft:levitation");
			}

			command(rock.level, "effect give " + id + " minecraft:levitation 1 " + amplifier + " true");
		}

		rock.lastAmp = amplifier;

		if (rock.age % 4 == 0) {
			double y = rock.box.minY() + rock.dy - 0.3;
			command(rock.level, String.format(Locale.ROOT, "particle minecraft:poof %.2f %.2f %.2f 0.8 0.1 0.8 0.02 5", cx, y, cz));
		}

		if (rock.age % 12 == 0) {
			command(rock.level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.hit block @a %.1f %.1f %.1f 2.0 0.5",
					cx, rock.box.minY() + rock.dy, cz));
		}
	}

	/** Players on the rock, or just above it on their way up (the lift gets ahead of the rock by a few blocks). */
	private static List<ServerPlayer> carried(Rock rock) {
		List<ServerPlayer> result = new ArrayList<>();

		for (ServerPlayer player : rock.level.players()) {
			if (!player.isAlive() || player.isSpectator()) {
				continue;
			}

			double x = player.getX();
			double y = player.getY();
			double z = player.getZ();

			if (x < rock.box.minX() - 0.35 || x > rock.box.maxX() + 1.35 || z < rock.box.minZ() - 0.35 || z > rock.box.maxZ() + 1.35) {
				continue;
			}

			if (y < rock.box.minY() + rock.dy - 0.5 || y > rock.box.maxY() + rock.dy + 1 + 5) {
				continue;
			}

			result.add(player);
		}

		return result;
	}

	/** Moves the whole rock to the given height above where it was generated (blocks only: nothing else is affected). */
	private static void shift(Rock rock, int newDy) {
		ServerLevel level = rock.level;
		BlockState air = Blocks.AIR.defaultBlockState();

		for (Cell cell : rock.cells) {
			level.setBlock(new BlockPos(rock.box.minX() + cell.x(), rock.box.minY() + rock.dy + cell.y(), rock.box.minZ() + cell.z()), air, SET_FLAGS);
		}

		for (Cell cell : rock.cells) {
			level.setBlock(new BlockPos(rock.box.minX() + cell.x(), rock.box.minY() + newDy + cell.y(), rock.box.minZ() + cell.z()), cell.state(), SET_FLAGS);
		}

		rock.dy = newDy;
	}

	private static void command(ServerLevel level, String command) {
		ArenaBoss.command(level, ArenaBoss.quietSource(level), command);
	}
}
