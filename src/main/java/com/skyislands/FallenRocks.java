package com.skyislands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Fallen rocks (structures sky_islands:fallen_rock_1..4, tag sky_islands:fallen_rocks): small piles of deepslate that lie on
 * the ground. Look at one (within normal block reach) and it gets a golden outline; right click it and it is recalled, like
 * the fallen sky island rocks in Tears of the Kingdom: the whole pile (real blocks) rises with you up to Y=430, hangs there
 * for a moment so you can run off and glide to a nearby island, then falls back down and becomes the same pile of rocks
 * again. After it has come down it has a short cooldown, during which it has no outline and cannot be launched.
 *
 * <ul>
 * <li>A rock only works while it is whole: if even one of its blocks is gone (or one is added) it stays an ordinary pile for
 * good. This is judged from the blocks themselves, so a rock that was broken and put back together exactly looks whole again
 * after the server restarts.</li>
 * <li>A rock needs a clear way up (no block with collision, and no fluid, above it; leaves do not count, they are broken
 * when the rock moves). A rock that would be generated with something above it is moved to a nearby spot (within
 * {@link #RELOCATE_RADIUS} blocks) that has a clear way up, and is only removed when there is no such spot.</li>
 * </ul>
 *
 * <p>The lift itself: the rock follows the rider's height block by block while the rider is held up by bending their gravity
 * whose gravity is bent (the attribute the geysers use) so that their speed eases down towards the top, with no hard stop.
 */
public final class FallenRocks {
	private static final TagKey<Structure> ROCKS = TagKey.create(Registries.STRUCTURE, SkyIslands.id("fallen_rocks"));
	/** The decorative piles of rock (fallen_rock_ruins1..3): only look like fallen rocks, so they can never be selected or launched. */
	private static final TagKey<Structure> RUINS = TagKey.create(Registries.STRUCTURE, SkyIslands.id("fallen_rock_ruins"));

	/** Players this close to a launched rock see the world in grayscale until the rock starts to fall. */
	private static final double FILTER_RADIUS = 10.0;
	/** How often (in ticks) it is worked out who is close enough. */
	private static final int FILTER_CHECK_TICKS = 4;
	/**
	 * What a rock does to a creature it falls onto: the damage of an anvil that has fallen about 20 blocks (an anvil does 2 damage
	 * for each block it fell, less the first one: 2 * 19 = 38).
	 */
	private static final float IMPACT_DAMAGE = 38.0F;

	/** The rock rises until the player standing on it is at this height. */
	private static final int TARGET_Y = 430;
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
	/** A rock that is covered at generation is moved to a clear spot at most this many blocks away. */
	private static final int RELOCATE_RADIUS = 10;
	/** Leaf blocks are broken this many blocks around the rock's footprint as well, so the rider is not caught on a branch. */
	private static final int LEAF_MARGIN = 1;
	/** Whether broken leaves drop their items (saplings, sticks, apples). Off: a whole tree of leaves would spawn many items. */
	private static final boolean LEAF_DROPS = false;
	/** The most blocks one rock can have (the largest of the four has 19), for finding a rock from its blocks. */
	private static final int MAX_ROCK_BLOCKS = 24;
	/** How often (in ticks) the server re-checks whether the rock a player looks at is ready (cooldown over, still whole). */
	private static final int HOVER_CHECK_TICKS = 5;
	/** Sanity limit for the looked-at block (the client only sends blocks in normal reach; vanilla checks the real click). */
	private static final double HOVER_MAX_RANGE = 7.0;
	private static final long[] NONE = new long[0];
	/** The gravity modifier that holds the riders up (the same attribute the geysers use). It is transient: never saved. */
	private static final Identifier LIFT_ID = SkyIslands.id("rock_lift");
	/** How much of the gap between a rider's speed and the wanted speed is closed each tick. */
	private static final double LIFT_GAIN = 0.3;
	/** The strongest upward pull, as (negative) gravity. */
	private static final double LIFT_MAX_THRUST = 0.25;
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
		/** Players held up by the lift (their gravity is bent while on this list), and where they were a tick ago. */
		final Set<UUID> lifted = new HashSet<>();
		final Map<UUID, Double> lastY = new HashMap<>();
		double fallDistance;
		double fallSpeed;
		/** Players who currently see the grayscale filter of this rock. */
		final Set<UUID> filtered = new HashSet<>();
		/** Creatures this rock has already hit on its way down (each one is hit once). */
		final Set<UUID> struck = new HashSet<>();
		/** The tag of every golden copy of this rock (the glow), and the block_state data of each of its blocks. */
		final String fxTag;
		List<String> fxStates;
		boolean halo;
		int haloDy;

		Rock(ServerLevel level, BoundingBox box, long key, List<Cell> cells) {
			this.level = level;
			this.box = box;
			this.key = key;
			this.cells = cells;
			this.fxTag = "sky_fx_" + Long.toUnsignedString(key);
		}
	}

	/** The rock a player is looking at: the block under their crosshair, and what was last sent to them about it. */
	private static final class Hover {
		long pos;
		long[] sent = NONE;
		int next;
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
	private static final Map<UUID, Hover> HOVER = new HashMap<>();
	/** For each player who sees the grayscale filter: the rocks (their keys) that cause it. */
	private static final Map<UUID, Set<Long>> FILTER = new HashMap<>();
	/** Whether the golden copies are bigger than the blocks (null: not tried yet), and whether they could be made at all. */
	private static Boolean fxEnlarged;
	private static boolean fxVerified;
	private static boolean fxBroken;
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
		PayloadTypeRegistry.serverboundPlay().register(RockHoverPayload.TYPE, RockHoverPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RockHighlightPayload.TYPE, RockHighlightPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RockRecallPayload.TYPE, RockRecallPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RockFilterPayload.TYPE, RockFilterPayload.CODEC);

		// golden copies that are found in the world while no rock is in the air were left behind (a crash, a world that was closed): remove them
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (ACTIVE.isEmpty() && ArenaBoss.tagsOf(entity).contains(RockFx.TAG)) {
				entity.discard();
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(RockHoverPayload.TYPE, (payload, context) -> onHover(context.player(), payload));
		UseBlockCallback.EVENT.register(FallenRocks::onUseBlock);
	}

	public static void clear() {
		ACTIVE.clear();
		HOVER.clear();
		FILTER.clear();
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
				clearFilter(rock);
				fxClear(rock);
				releaseLift(rock);
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
			updateHover(server, now);
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

				// The start's own bounding box is inflated by terrain adaptation (about 12 blocks all round): the boxes
				// of its pieces are the real size of the rock.
				BoundingBox pieces = pieceBox(start);

				if (pieces == null) {
					continue;
				}

				BlockPos center = new BlockPos((pieces.minX() + pieces.maxX()) / 2, (pieces.minY() + pieces.maxY()) / 2, (pieces.minZ() + pieces.maxZ()) / 2);
				StructureStart found = level.structureManager().getStructureWithPieceAt(center, ROCKS);

				if (!found.isValid() || pieceBox(found) == null || !sameBox(pieceBox(found), pieces)) {
					continue;
				}

				if (!columnsLoaded(level, pieces)) {
					again = true;
					continue;
				}

				// where the rock really is now (it may already have been moved a block on an earlier try); null: gone
				BoundingBox box = actualBox(level, pieces);

				if (box == null) {
					continue;
				}

				box = settle(level, box);
				List<Cell> settled = readCells(level, box);

				if (settled.size() == expectedBlocks(box)) {
					repairGround(level, box, positionsOf(box, settled), false);
				}

				BlockPos blocker = findBlocker(level, box);

				if (blocker == null) {
					continue;
				}

				if (!surroundingsLoaded(level, box) && next.tries < GEN_MAX_TRIES) {
					// a spot to move to may be in a chunk that is not there yet
					again = true;
					continue;
				}

				coverRock(level, box, blocker);
			}

			if (again && next.tries < GEN_MAX_TRIES) {
				QUEUE.add(new Generated(level, next.pos, next.tries + 1));
			}
		}
	}

	private static BoundingBox pieceBox(StructureStart start) {
		BoundingBox result = null;

		for (StructurePiece piece : start.getPieces()) {
			BoundingBox b = piece.getBoundingBox();
			result = result == null ? b : new BoundingBox(Math.min(result.minX(), b.minX()), Math.min(result.minY(), b.minY()), Math.min(result.minZ(), b.minZ()),
					Math.max(result.maxX(), b.maxX()), Math.max(result.maxY(), b.maxY()), Math.max(result.maxZ(), b.maxZ()));
		}

		return result;
	}

	/** The box of the rock's blocks (found from them, a couple of blocks above and below the piece box too), or null if none. */
	private static BoundingBox actualBox(ServerLevel level, BoundingBox pieces) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = pieces.minX(); x <= pieces.maxX(); x++) {
			for (int y = pieces.minY() - 2; y <= pieces.maxY() + 2; y++) {
				for (int z = pieces.minZ(); z <= pieces.maxZ(); z++) {
					pos.set(x, y, z);

					if (isRockBlock(level.getBlockState(pos))) {
						return floodBox(level, pos.immutable());
					}
				}
			}
		}

		return null;
	}

	/** Natural ground that something can stand on (not rock blocks, leaves, logs, fluid). */
	private static boolean isGround(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && state.getFluidState().isEmpty() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)
				&& !isRockBlock(state) && state.isFaceSturdy(level, pos, Direction.UP);
	}

	private static final Direction[] SIDES = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };

	/** True when the bottom of the rock is sunk into the ground: most of the blocks next to its bottom layer are ground. */
	private static boolean isEmbedded(ServerLevel level, BoundingBox box, List<Cell> cells) {
		Set<Long> own = new HashSet<>();

		for (Cell cell : cells) {
			own.add(BlockPos.asLong(box.minX() + cell.x(), box.minY() + cell.y(), box.minZ() + cell.z()));
		}

		int solid = 0;
		int total = 0;

		for (Cell cell : cells) {
			if (cell.y() != 0) {
				continue;
			}

			BlockPos pos = new BlockPos(box.minX() + cell.x(), box.minY(), box.minZ() + cell.z());

			for (Direction side : SIDES) {
				BlockPos n = pos.relative(side);

				if (own.contains(n.asLong())) {
					continue;
				}

				total++;

				if (isGround(level, n)) {
					solid++;
				}
			}
		}

		return total > 0 && solid * 2 > total;
	}

	/** True when no block of the bottom layer has anything solid under it. */
	private static boolean isFloating(ServerLevel level, BoundingBox box, List<Cell> cells) {
		for (Cell cell : cells) {
			if (cell.y() == 0 && isGround(level, new BlockPos(box.minX() + cell.x(), box.minY() - 1, box.minZ() + cell.z()))) {
				return false;
			}
		}

		return true;
	}

	/** What to put into an empty spot in the ground: what is next to it, or what is under it. Null when there is nothing fitting. */
	private static BlockState groundFill(ServerLevel level, BlockPos pos, Set<Long> own) {
		for (Direction side : SIDES) {
			BlockPos n = pos.relative(side);

			if (!own.contains(n.asLong()) && isGround(level, n)) {
				return level.getBlockState(n);
			}
		}

		BlockPos below = pos.below();
		return isGround(level, below) ? level.getBlockState(below) : null;
	}

	/**
	 * The rock's structure carves air out of the ground wherever the rock has no block in its bottom layer (the raised ends of
	 * a rock). Where that left a hole in the ground under the rock (and, for a rock that has just been taken out of the ground,
	 * in its bottom layer) the ground is put back. Only holes with ground under them are filled, so nothing is left hanging.
	 */
	private static void repairGround(ServerLevel level, BoundingBox box, Set<Long> own, boolean includeBottomLayer) {
		for (int y = box.minY() - 1; y <= (includeBottomLayer ? box.minY() : box.minY() - 1); y++) {
			for (int x = box.minX(); x <= box.maxX(); x++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					BlockPos pos = new BlockPos(x, y, z);

					if (own.contains(pos.asLong()) || !level.getBlockState(pos).isAir() || !isGround(level, pos.below())) {
						continue;
					}

					BlockState fill = groundFill(level, pos, own);

					if (fill != null) {
						level.setBlock(pos, fill, SET_FLAGS);
					}
				}
			}
		}
	}

	private static Set<Long> positionsOf(BoundingBox box, List<Cell> cells) {
		Set<Long> own = new HashSet<>();

		for (Cell cell : cells) {
			own.add(BlockPos.asLong(box.minX() + cell.x(), box.minY() + cell.y(), box.minZ() + cell.z()));
		}

		return own;
	}

	/** Takes a rock out of the world. A rock that was sunk into the ground leaves ground behind, not a hole. */
	private static void vacate(ServerLevel level, BoundingBox box, List<Cell> cells) {
		boolean embedded = isEmbedded(level, box, cells);
		Set<Long> own = new HashSet<>();

		for (Cell cell : cells) {
			own.add(BlockPos.asLong(box.minX() + cell.x(), box.minY() + cell.y(), box.minZ() + cell.z()));
		}

		List<BlockState> fills = new ArrayList<>();

		for (Cell cell : cells) {
			BlockState fill = Blocks.AIR.defaultBlockState();

			if (embedded) {
				BlockPos pos = new BlockPos(box.minX() + cell.x(), box.minY() + cell.y(), box.minZ() + cell.z());
				BlockPos below = pos.below();

				if (cell.y() == 0 && isGround(level, below)) {
					fill = level.getBlockState(below);
				} else {
					for (Direction side : SIDES) {
						BlockPos n = pos.relative(side);

						if (!own.contains(n.asLong()) && isGround(level, n)) {
							fill = level.getBlockState(n);
							break;
						}
					}
				}
			}

			fills.add(fill);
		}

		for (int i = 0; i < cells.size(); i++) {
			Cell cell = cells.get(i);
			level.setBlock(new BlockPos(box.minX() + cell.x(), box.minY() + cell.y(), box.minZ() + cell.z()), fills.get(i), SET_FLAGS);
		}

		repairGround(level, box, own, embedded);
	}

	/**
	 * Makes the rock sit on the ground: a rock sunk into it is lifted a block (and the ground it replaced is put back), one
	 * hanging in the air is lowered. Returns the box the rock is in afterwards.
	 */
	private static BoundingBox settle(ServerLevel level, BoundingBox box) {
		int last = 0;

		for (int i = 0; i < 3; i++) {
			List<Cell> cells = readCells(level, box);

			if (cells.size() != expectedBlocks(box)) {
				return box;
			}

			int dy = isEmbedded(level, box, cells) ? 1 : (isFloating(level, box, cells) ? -1 : 0);

			if (dy == 0 || dy == -last) {
				return box;
			}

			vacate(level, box, cells);

			for (Cell cell : cells) {
				level.setBlock(new BlockPos(box.minX() + cell.x(), box.minY() + dy + cell.y(), box.minZ() + cell.z()), cell.state(), SET_FLAGS);
			}

			box = new BoundingBox(box.minX(), box.minY() + dy, box.minZ(), box.maxX(), box.maxY() + dy, box.maxZ());
			last = dy;
		}

		return box;
	}

	private static boolean sameBox(BoundingBox a, BoundingBox b) {
		return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
				&& a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
	}

	private static boolean columnsLoaded(ServerLevel level, BoundingBox box) {
		return level.hasChunkAt(new BlockPos(box.minX(), 64, box.minZ())) && level.hasChunkAt(new BlockPos(box.maxX(), 64, box.minZ()))
				&& level.hasChunkAt(new BlockPos(box.minX(), 64, box.maxZ())) && level.hasChunkAt(new BlockPos(box.maxX(), 64, box.maxZ()));
	}

	/**
	 * The first block with collision, or fluid, above the rock on the way up (to just above the top of the rise), or null when the
	 * way is clear. Leaves do not count: they are broken when the rock moves.
	 */
	private static BlockPos findBlocker(ServerLevel level, BoundingBox box) {
		int height = box.maxY() - box.minY() + 1;
		int top = TARGET_Y + height + CLEARANCE_ABOVE_TARGET;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				for (int y = box.maxY() + 1; y <= top; y++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);

					if (state.isAir() || state.is(BlockTags.LEAVES)) {
						continue;
					}

					if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, pos).isEmpty()) {
						return pos.immutable();
					}
				}
			}
		}

		return null;
	}

	/** The rock's own blocks, relative to the minimum corner of the box. */
	private static List<Cell> readCells(ServerLevel level, BoundingBox box) {
		List<Cell> cells = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);

					if (isRockBlock(state)) {
						cells.add(new Cell(x - box.minX(), y - box.minY(), z - box.minZ(), state));
					}
				}
			}
		}

		return cells;
	}

	private static boolean surroundingsLoaded(ServerLevel level, BoundingBox box) {
		for (int x = (box.minX() - RELOCATE_RADIUS) >> 4; x <= (box.maxX() + RELOCATE_RADIUS) >> 4; x++) {
			for (int z = (box.minZ() - RELOCATE_RADIUS) >> 4; z <= (box.maxZ() + RELOCATE_RADIUS) >> 4; z++) {
				if (!level.hasChunkAt(new BlockPos(x << 4, 64, z << 4))) {
					return false;
				}
			}
		}

		return true;
	}

	/** Spots within the relocation radius, nearest first (offsets from where the rock was generated). */
	private static final List<int[]> OFFSETS = new ArrayList<>();

	static {
		for (int dx = -RELOCATE_RADIUS; dx <= RELOCATE_RADIUS; dx++) {
			for (int dz = -RELOCATE_RADIUS; dz <= RELOCATE_RADIUS; dz++) {
				if ((dx != 0 || dz != 0) && dx * dx + dz * dz <= RELOCATE_RADIUS * RELOCATE_RADIUS) {
					OFFSETS.add(new int[] { dx, dz });
				}
			}
		}

		OFFSETS.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
	}

	/** A rock that has something above it: moved to the nearest spot with a clear way up, or removed when there is none. */
	private static void coverRock(ServerLevel level, BoundingBox box, BlockPos blocker) {
		List<Cell> cells = readCells(level, box);
		String blockedBy = BuiltInRegistries.BLOCK.getKey(level.getBlockState(blocker).getBlock()) + " at " + blocker.toShortString();
		int width = box.maxX() - box.minX() + 1;
		int depth = box.maxZ() - box.minZ() + 1;
		int height = box.maxY() - box.minY() + 1;

		// first only flat ground, then ground that differs by one block
		for (int maxDiff = 0; maxDiff <= 1; maxDiff++) {
			for (int[] offset : OFFSETS) {
				// never overlapping where it was (that would make the old structure box see the new blocks)
				if (Math.abs(offset[0]) < width && Math.abs(offset[1]) < depth) {
					continue;
				}

				int nx = box.minX() + offset[0];
				int nz = box.minZ() + offset[1];
				int base = clearSpot(level, nx, nz, width, depth, height, maxDiff, cells);

				if (base == Integer.MIN_VALUE) {
					continue;
				}

				removeRock(level, box);

				for (Cell cell : cells) {
					level.setBlock(new BlockPos(nx + cell.x(), base + cell.y(), nz + cell.z()), cell.state(), SET_FLAGS);
				}

				SkyIslands.LOGGER.info("Sky Islands: a fallen rock at {} {} {} had {} above it and was moved to {} {} {}",
						box.minX(), box.minY(), box.minZ(), blockedBy, nx, base, nz);
				return;
			}
		}

		removeRock(level, box);
		SkyIslands.LOGGER.info("Sky Islands: a fallen rock at {} {} {} had {} above it and no clear spot within {} blocks, so it was removed",
				box.minX(), box.minY(), box.minZ(), blockedBy, RELOCATE_RADIUS);
	}

	/**
	 * The height the bottom of the rock would stand at if it were placed with its minimum corner at (x, z): on solid ground that
	 * is flat enough, with room for its blocks and nothing above it. Integer.MIN_VALUE when it cannot go there.
	 */
	private static int clearSpot(ServerLevel level, int x, int z, int width, int depth, int height, int maxDiff, List<Cell> cells) {
		int low = Integer.MAX_VALUE;
		int high = Integer.MIN_VALUE;

		for (int cx = x; cx < x + width; cx++) {
			for (int cz = z; cz < z + depth; cz++) {
				if (!level.hasChunkAt(new BlockPos(cx, 64, cz))) {
					return Integer.MIN_VALUE;
				}

				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
				low = Math.min(low, top);
				high = Math.max(high, top);
			}
		}

		if (high - low > maxDiff) {
			return Integer.MIN_VALUE;
		}

		for (int cx = x; cx < x + width; cx++) {
			for (int cz = z; cz < z + depth; cz++) {
				BlockPos groundPos = new BlockPos(cx, high - 1, cz);
				BlockState ground = level.getBlockState(groundPos);

				if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) < high) {
					// a column one block lower than the rest (only allowed in the second pass): the rock hangs a block above it
					continue;
				}

				if (!ground.getFluidState().isEmpty() || ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS)
						|| !ground.isFaceSturdy(level, groundPos, Direction.UP)) {
					return Integer.MIN_VALUE;
				}
			}
		}

		for (Cell cell : cells) {
			BlockPos pos = new BlockPos(x + cell.x(), high + cell.y(), z + cell.z());
			BlockState state = level.getBlockState(pos);

			if (!state.getFluidState().isEmpty() || !(state.isAir() || state.is(BlockTags.LEAVES) || state.canBeReplaced())) {
				return Integer.MIN_VALUE;
			}
		}

		if (findBlocker(level, new BoundingBox(x, high, z, x + width - 1, high + height - 1, z + depth - 1)) != null) {
			return Integer.MIN_VALUE;
		}

		return high;
	}

	/** A rock that cannot be launched and has nowhere to go: its blocks are taken out again. */
	private static void removeRock(ServerLevel level, BoundingBox box) {
		vacate(level, box, readCells(level, box));
	}

	// ---------------------------------------------------------------- recognising a rock

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
		boolean hasTuff = false;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		// the rock's own blocks (only these: the ground around and under it is never carried)
		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);

					if (isRockBlock(state)) {
						rockBlocks++;
						hasTuff |= state.is(Blocks.CHISELED_TUFF);
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

		// all four rocks have a chiseled tuff block: a pile of plain deepslate somebody built is not a rock
		if (rockBlocks != expected || !hasTuff) {
			DEAD.add(key);
			return null;
		}

		if (findBlocker(level, box) != null) {
			return null;
		}

		return new Rock(level, box, key, cells);
	}

	// ---------------------------------------------------------------- finding a rock, looking at it, launching it

	/** The box of the rock the given block belongs to, found from its blocks (a rock may have been moved). Null if none. */
	private static BoundingBox locate(ServerLevel level, BlockPos pos) {
		return floodBox(level, pos);
	}

	private static BoundingBox floodBox(ServerLevel level, BlockPos pos) {
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(pos.asLong());
		queue.add(pos);
		int minX = pos.getX();
		int minY = pos.getY();
		int minZ = pos.getZ();
		int maxX = minX;
		int maxY = minY;
		int maxZ = minZ;

		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}

						BlockPos n = p.offset(dx, dy, dz);

						if (seen.contains(n.asLong()) || !level.hasChunkAt(n) || !isRockBlock(level.getBlockState(n))) {
							continue;
						}

						if (seen.size() >= MAX_ROCK_BLOCKS) {
							return null;
						}

						seen.add(n.asLong());
						queue.add(n);
					}
				}
			}
		}

		return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** True while the block is part of a rock that is up in the air (such a rock is never found again as a new one). */
	private static boolean isMoving(BlockPos pos) {
		for (Rock rock : ACTIVE.values()) {
			if (rock.dy > 0 && pos.getX() >= rock.box.minX() && pos.getX() <= rock.box.maxX()
					&& pos.getZ() >= rock.box.minZ() && pos.getZ() <= rock.box.maxZ()
					&& pos.getY() >= rock.box.minY() + rock.dy && pos.getY() <= rock.box.maxY() + rock.dy + 1) {
				return true;
			}
		}

		return false;
	}

	/** The rock this block belongs to if the player may launch it right now (in reach, whole, clear way up, not cooling down). */
	private static Rock readyRock(ServerLevel level, ServerPlayer player, BlockPos pos, int now) {
		if (!Level.OVERWORLD.equals(level.dimension()) || player.isSpectator() || !player.isAlive() || player.getY() > TARGET_Y - 20) {
			return null;
		}

		if (player.getEyePosition().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > HOVER_MAX_RANGE * HOVER_MAX_RANGE) {
			return null;
		}

		if (!level.hasChunkAt(pos) || !isRockBlock(level.getBlockState(pos)) || isMoving(pos)) {
			return null;
		}

		// the decorative ruins are built of the same blocks as a rock but can never be selected or launched
		if (level.structureManager().getStructureWithPieceAt(pos, RUINS).isValid()) {
			return null;
		}

		BoundingBox box = locate(level, pos);

		if (box == null) {
			return null;
		}

		long key = BlockPos.asLong(box.minX(), box.minY(), box.minZ());

		if (ACTIVE.containsKey(key) || COOLDOWN.getOrDefault(key, 0) > now) {
			return null;
		}

		return evaluate(level, box, key);
	}

	private static long[] rockBlocks(Rock rock) {
		List<Long> list = new ArrayList<>();

		for (Cell cell : rock.cells) {
			if (isRockBlock(cell.state())) {
				list.add(BlockPos.asLong(rock.box.minX() + cell.x(), rock.box.minY() + cell.y(), rock.box.minZ() + cell.z()));
			}
		}

		long[] result = new long[list.size()];

		for (int i = 0; i < result.length; i++) {
			result[i] = list.get(i);
		}

		Arrays.sort(result);
		return result;
	}

	private static void onHover(ServerPlayer player, RockHoverPayload payload) {
		UUID id = player.getUUID();

		if (!payload.has()) {
			Hover hover = HOVER.remove(id);

			if (hover != null && hover.sent.length > 0) {
				sendHighlight(player, NONE);
			}

			return;
		}

		Hover hover = HOVER.computeIfAbsent(id, k -> new Hover());

		if (hover.pos != payload.pos()) {
			hover.pos = payload.pos();
			hover.next = 0;
		}
	}

	private static void sendHighlight(ServerPlayer player, long[] blocks) {
		List<Long> list = new ArrayList<>(blocks.length);
		int top = Integer.MIN_VALUE;

		for (long block : blocks) {
			list.add(block);
			top = Math.max(top, BlockPos.of(block).getY());
		}

		// how far up the rock goes from where it stands (the path the client shows)
		int rise = blocks.length == 0 ? 0 : Math.max(0, TARGET_Y - (top + 1));
		ServerPlayNetworking.send(player, new RockHighlightPayload(list, rise));
	}

	/** Tells each player who is looking at a rock whether it is ready (outline on) or not (outline off), when that changes. */
	private static void updateHover(MinecraftServer server, int now) {
		Iterator<Map.Entry<UUID, Hover>> it = HOVER.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<UUID, Hover> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

			if (player == null) {
				it.remove();
				continue;
			}

			Hover hover = entry.getValue();

			if (hover.next > now) {
				continue;
			}

			hover.next = now + HOVER_CHECK_TICKS;
			long[] blocks = NONE;

			if (player.level() instanceof ServerLevel level) {
				Rock rock = readyRock(level, player, BlockPos.of(hover.pos), now);

				if (rock != null) {
					blocks = rockBlocks(rock);
				}
			}

			if (!Arrays.equals(blocks, hover.sent)) {
				hover.sent = blocks;
				sendHighlight(player, blocks);
			}
		}
	}

	/** Right click on a ready rock launches it (the click is used up, so no block is placed against the rock). */
	private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
		if (disabled || hand != InteractionHand.MAIN_HAND || !(world instanceof ServerLevel level) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		int now = level.getServer().getTickCount();
		Rock rock = readyRock(level, serverPlayer, hit.getBlockPos(), now);

		if (rock == null || !start(level, serverPlayer, rock)) {
			return InteractionResult.PASS;
		}

		Hover hover = HOVER.get(player.getUUID());

		if (hover != null) {
			// the outline goes away at once
			hover.next = 0;
		}

		return InteractionResult.SUCCESS;
	}

	// ---------------------------------------------------------------- the golden copies (the glow)

	/** Makes the block_state data of the rock's blocks the first time it is needed. */
	private static boolean fxReady(Rock rock) {
		if (fxBroken) {
			return false;
		}

		if (rock.fxStates == null) {
			List<String> states = new ArrayList<>();

			for (Cell cell : rock.cells) {
				states.add(RockFx.blockState(cell.state()));
			}

			rock.fxStates = states;
		}

		return true;
	}

	/**
	 * Summons one golden glowing copy of every block of the rock, as it stands {@code dy} blocks up. {@code halo}: each copy
	 * also gets its own tag, so the glow can follow the rock block by block. Returns false when they did not appear.
	 */
	private static boolean fxSpawn(Rock rock, int dy, String groupTag, boolean halo, boolean enlarged) {
		for (int i = 0; i < rock.cells.size(); i++) {
			Cell cell = rock.cells.get(i);
			List<String> tags = new ArrayList<>(List.of(RockFx.TAG, rock.fxTag, groupTag));

			if (halo) {
				tags.add(rock.fxTag + "_h" + i);
			}

			command(rock.level, RockFx.summon(rock.box.minX() + cell.x(), rock.box.minY() + dy + cell.y(), rock.box.minZ() + cell.z(),
					rock.fxStates.get(i), tags, enlarged));
		}

		if (fxVerified) {
			return true;
		}

		// the commands say nothing when they fail: look for what they should have made
		AABB around = new AABB(rock.box.minX() - 1, rock.box.minY() + dy - 1, rock.box.minZ() - 1,
				rock.box.maxX() + 2, rock.box.maxY() + dy + 2, rock.box.maxZ() + 2);
		return !rock.level.getEntitiesOfClass(Entity.class, around, e -> ArenaBoss.tagsOf(e).contains(groupTag)).isEmpty();
	}

	/** The glow: golden copies of the rock's blocks around it, from the moment it is launched until it starts to fall. */
	private static void fxHaloOn(Rock rock) {
		if (!fxReady(rock)) {
			return;
		}

		String group = rock.fxTag + "_halo";
		boolean ok;

		if (fxEnlarged == null) {
			// the first time: find out which kind of copy the game accepts
			fxEnlarged = Boolean.TRUE;
			ok = fxSpawn(rock, rock.dy, group, true, true);

			if (!ok) {
				command(rock.level, RockFx.kill(rock.fxTag));
				fxEnlarged = Boolean.FALSE;
				ok = fxSpawn(rock, rock.dy, group, true, false);
			}

			if (ok) {
				fxVerified = true;
				SkyIslands.LOGGER.info("Sky Islands: the golden glow of a launched rock works ({} copies)", fxEnlarged ? "slightly enlarged" : "plain");
			}
		} else {
			ok = fxSpawn(rock, rock.dy, group, true, fxEnlarged);
		}

		if (!ok) {
			fxBroken = true;
			command(rock.level, RockFx.kill(rock.fxTag));
			SkyIslands.LOGGER.warn("Sky Islands: the golden copies of a launched rock (the glow) could not be made: the game did not accept a block display with this block_state data: {}",
					rock.fxStates.isEmpty() ? "(none)" : rock.fxStates.get(0));
			return;
		}

		rock.halo = true;
		rock.haloDy = rock.dy;
	}

	/** The glow follows the rock as it moves. */
	private static void fxFollow(Rock rock) {
		if (!rock.halo || rock.dy == rock.haloDy) {
			return;
		}

		rock.haloDy = rock.dy;

		for (int i = 0; i < rock.cells.size(); i++) {
			Cell cell = rock.cells.get(i);
			command(rock.level, RockFx.teleport(rock.fxTag + "_h" + i, rock.box.minX() + cell.x(), rock.box.minY() + rock.dy + cell.y(),
					rock.box.minZ() + cell.z()));
		}
	}

	/** The glow ends when the rock starts to fall. */
	private static void fxHaloOff(Rock rock) {
		if (rock.halo) {
			rock.halo = false;
			command(rock.level, RockFx.kill(rock.fxTag + "_halo"));
		}
	}

	/** Removes every golden copy of this rock. */
	private static void fxClear(Rock rock) {
		if (rock.halo) {
			command(rock.level, RockFx.kill(rock.fxTag));
		}

		rock.halo = false;
	}

	// ---------------------------------------------------------------- the golden light (the look of a recall)

	/** The blue streak that follows a falling rock: a column of cold blue flame above it, longer the faster it falls. */
	private static void streak(Rock rock) {
		double w = rock.box.maxX() - rock.box.minX() + 1;
		double d = rock.box.maxZ() - rock.box.minZ() + 1;
		double cx = rock.box.minX() + w / 2.0;
		double cz = rock.box.minZ() + d / 2.0;
		double top = rock.box.maxY() + rock.dy + 1;
		double length = 6.0 + rock.fallSpeed * 3.0;
		rock.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, cx, top + length / 2.0, cz, 16, w / 3.0, length / 2.0, d / 3.0, 0.0);
		rock.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, cx, top + length / 2.0, cz, 4, w / 3.0, length / 2.0, d / 3.0, 0.0);
	}

	/** When a rock hits the ground: waves of blue and green spread out from it along the ground, one ring after the other. */
	private static void landingWaves(Rock rock) {
		double w = rock.box.maxX() - rock.box.minX() + 1;
		double d = rock.box.maxZ() - rock.box.minZ() + 1;
		double cx = rock.box.minX() + w / 2.0;
		double cz = rock.box.minZ() + d / 2.0;
		double y = rock.box.minY() + 0.2;
		double radius = Math.max(w, d) / 2.0 + 0.3;
		double[] speeds = {0.25, 0.45, 0.65};

		for (int ring = 0; ring < speeds.length; ring++) {
			for (int i = 0; i < 36; i++) {
				double angle = Math.PI * 2.0 * i / 36.0;
				double dx = Math.cos(angle);
				double dz = Math.sin(angle);
				// the particles fly out from the ring (count 0: the three numbers are the direction), blue, green, blue
				rock.level.sendParticles(ring == 1 ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SOUL_FIRE_FLAME,
						cx + dx * radius, y, cz + dz * radius, 0, dx, 0.02, dz, speeds[ring]);
			}
		}
	}


	/** Golden sparks over the rock where it is now: {@code gold} gold glints and {@code bright} brighter ones, flying out at {@code speed}. */
	private static void sparks(Rock rock, int gold, int bright, double speed, double spread) {
		double w = rock.box.maxX() - rock.box.minX() + 1;
		double h = rock.box.maxY() - rock.box.minY() + 1;
		double d = rock.box.maxZ() - rock.box.minZ() + 1;
		double cx = rock.box.minX() + w / 2.0;
		double cy = rock.box.minY() + rock.dy + h / 2.0;
		double cz = rock.box.minZ() + d / 2.0;
		rock.level.sendParticles(ParticleTypes.WAX_ON, cx, cy, cz, gold, w / 2.0 + spread, h / 2.0 + spread, d / 2.0 + spread, speed);

		if (bright > 0) {
			rock.level.sendParticles(ParticleTypes.END_ROD, cx, cy, cz, bright, w / 2.0 + spread, h / 2.0 + spread, d / 2.0 + spread, speed);
		}
	}

	/** The golden light the rising rock leaves behind it: a few glints under its base every tick. */
	private static void trail(Rock rock) {
		double w = rock.box.maxX() - rock.box.minX() + 1;
		double d = rock.box.maxZ() - rock.box.minZ() + 1;
		double cx = rock.box.minX() + w / 2.0;
		double cz = rock.box.minZ() + d / 2.0;
		double bottom = rock.box.minY() + rock.dy;
		rock.level.sendParticles(ParticleTypes.WAX_ON, cx, bottom, cz, 4, w / 2.0, 0.2, d / 2.0, 0.02);

		if (rock.age % 2 == 0) {
			rock.level.sendParticles(ParticleTypes.END_ROD, cx, bottom, cz, 1, w / 2.0, 0.2, d / 2.0, 0.01);
		}
	}

	// ---------------------------------------------------------------- the grayscale filter

	/** Works out who is close enough to the rock to see the filter, and tells the players whose situation changed. */
	private static void updateFilter(Rock rock) {
		double cx = (rock.box.minX() + rock.box.maxX() + 1) / 2.0;
		double cy = rock.box.minY() + rock.dy + (rock.box.maxY() - rock.box.minY() + 1) / 2.0;
		double cz = (rock.box.minZ() + rock.box.maxZ() + 1) / 2.0;
		Set<UUID> near = new HashSet<>();
		Map<UUID, ServerPlayer> players = new HashMap<>();

		for (ServerPlayer player : rock.level.players()) {
			double dx = player.getX() - cx;
			double dy = player.getY() - cy;
			double dz = player.getZ() - cz;

			if (player.isAlive() && dx * dx + dy * dy + dz * dz <= FILTER_RADIUS * FILTER_RADIUS) {
				near.add(player.getUUID());
				players.put(player.getUUID(), player);
			}
		}

		for (UUID id : near) {
			if (rock.filtered.add(id)) {
				setFilter(rock, id, players.get(id), true);
			}
		}

		for (UUID id : new ArrayList<>(rock.filtered)) {
			if (!near.contains(id)) {
				rock.filtered.remove(id);
				setFilter(rock, id, rock.level.getPlayerByUUID(id) instanceof ServerPlayer p ? p : null, false);
			}
		}
	}

	/** Takes the filter of this rock away from everybody (the rock started to fall, or the flight ended). */
	private static void clearFilter(Rock rock) {
		for (UUID id : new ArrayList<>(rock.filtered)) {
			rock.filtered.remove(id);
			setFilter(rock, id, rock.level.getPlayerByUUID(id) instanceof ServerPlayer p ? p : null, false);
		}
	}

	/** A player sees the filter as long as at least one rock causes it: the client is only told when that changes. */
	private static void setFilter(Rock rock, UUID id, ServerPlayer player, boolean on) {
		Set<Long> causes = FILTER.computeIfAbsent(id, k -> new HashSet<>());

		if (on) {
			boolean first = causes.isEmpty();
			causes.add(rock.key);

			if (first && player != null) {
				ServerPlayNetworking.send(player, new RockFilterPayload(true));
			}

			return;
		}

		causes.remove(rock.key);

		if (causes.isEmpty()) {
			FILTER.remove(id);

			if (player != null) {
				ServerPlayNetworking.send(player, new RockFilterPayload(false));
			}
		}
	}

	// ---------------------------------------------------------------- the impact

	/** A rock that is about to move down hurts the creatures in its way, like a falling anvil (each one once per fall). */
	private static void crush(Rock rock, int newDy) {
		ServerLevel level = rock.level;
		double oldBottom = rock.box.minY() + rock.dy;
		double newBottom = rock.box.minY() + newDy;
		Set<Long> columns = new HashSet<>();

		for (Cell cell : rock.cells) {
			int x = rock.box.minX() + cell.x();
			int z = rock.box.minZ() + cell.z();

			if (!columns.add(BlockPos.asLong(x, 0, z))) {
				continue;
			}

			// the space the rock is about to pass through, above this column of it
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(x, newBottom, z, x + 1.0, oldBottom, z + 1.0))) {
				if (!entity.isAlive() || (entity instanceof Player player && player.isSpectator()) || !rock.struck.add(entity.getUUID())) {
					continue;
				}

				entity.hurtServer(level, level.damageSources().fallingBlock(null), IMPACT_DAMAGE);
			}
		}
	}

	/** Tells the player who launched the rock which stage of its flight it is in (for the sounds on their client). */
	private static void notifyDriver(Rock rock, int event) {
		if (rock.driver != null && rock.level.getPlayerByUUID(rock.driver) instanceof ServerPlayer driver) {
			ServerPlayNetworking.send(driver, new RockRecallPayload(event));
		}
	}

	private static boolean start(ServerLevel level, ServerPlayer player, Rock rock) {
		rock.driver = player.getUUID();
		// on the rock: the lift starts from the player's feet; beside it: from the top of the rock
		rock.startFeet = carried(rock).contains(player) ? player.getY() : rock.box.maxY() + 1.0;
		rock.total = TARGET_Y - (int) Math.floor(rock.startFeet + 1.0E-4);

		if (rock.total < 10) {
			return false;
		}

		ACTIVE.put(rock.key, rock);
		notifyDriver(rock, RockRecallPayload.RISE);
		updateFilter(rock);
		sparks(rock, 40, 8, 0.15, 0.3);
		fxHaloOn(rock);
		// whatever leaves hang over the rock are gone before it rises into them
		clearLeaves(level, rock.box, rock.box.maxY() + 1, TARGET_Y + (rock.box.maxY() - rock.box.minY() + 1) + CLEARANCE_ABOVE_TARGET);
		command(level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.break block @a %.1f %.1f %.1f 3.0 0.5",
				player.getX(), player.getY(), player.getZ()));
		command(level, String.format(Locale.ROOT, "playsound minecraft:entity.wind_charge.wind_burst block @a %.1f %.1f %.1f 2.0 0.6",
				player.getX(), player.getY(), player.getZ()));
		return true;
	}

	/** Breaks every leaf block in the rock's columns (and the ring of blocks around them) between the two heights. */
	private static void clearLeaves(ServerLevel level, BoundingBox box, int fromY, int toY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = box.minX() - LEAF_MARGIN; x <= box.maxX() + LEAF_MARGIN; x++) {
			for (int z = box.minZ() - LEAF_MARGIN; z <= box.maxZ() + LEAF_MARGIN; z++) {
				if (!level.hasChunkAt(pos.set(x, 64, z))) {
					continue;
				}

				for (int y = fromY; y <= toY; y++) {
					pos.set(x, y, z);

					if (level.getBlockState(pos).is(BlockTags.LEAVES)) {
						level.destroyBlock(pos.immutable(), LEAF_DROPS);
					}
				}
			}
		}
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
				notifyDriver(rock, RockRecallPayload.END);
				clearFilter(rock);
				fxClear(rock);
				releaseLift(rock);

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

		if (rock.phase != Phase.FALLING && rock.age % FILTER_CHECK_TICKS == 0) {
			updateFilter(rock);
		}

		if (rock.phase == Phase.FALLING) {
			streak(rock);
		}

		if (rock.phase == Phase.RISING) {
			trail(rock);
		} else if (rock.phase == Phase.HOVER && rock.age % 3 == 0) {
			sparks(rock, 2, 0, 0.01, 0.4);
		}

		switch (rock.phase) {
			case RISING -> rise(rock, cx, cz);
			case HOVER -> {
				rock.timer++;

				if (rock.timer >= HOVER_TICKS - 40 && rock.timer % 10 == 0) {
					command(rock.level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.hit block @a %.1f %.1f %.1f 3.0 0.6",
							cx, (double) (rock.box.minY() + rock.dy), cz));
					sparks(rock, 8, 2, 0.06, 0.3);
				}

				if (rock.timer >= HOVER_TICKS) {
					// leaves that have appeared in the way down are broken before the rock drops
					clearLeaves(rock.level, rock.box, rock.box.minY(), rock.box.maxY() + rock.dy + 1);
					rock.phase = Phase.FALLING;
					notifyDriver(rock, RockRecallPayload.FALL);
					// the filter ends as the rock starts to fall, and the sparks fly off it to show that the effect has worn off
					clearFilter(rock);
					sparks(rock, 90, 25, 0.35, 0.3);
					fxHaloOff(rock);
					rock.struck.clear();
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
					landingWaves(rock);
					return false;
				}
			}
		}

		fxFollow(rock);
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

			releaseLift(rock);

			for (ServerPlayer player : carried) {
				// the rider has a little upward speed left when the lift lets go: no fall damage from it
				command(rock.level, "effect give " + player.getUUID() + " minecraft:slow_falling 3 0 true");
			}

			command(rock.level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.break block @a %.1f %.1f %.1f 3.0 0.8",
					cx, (double) (rock.box.minY() + rock.dy), cz));
			rock.phase = Phase.HOVER;
			notifyDriver(rock, RockRecallPayload.PEAK);
			rock.timer = 0;
			return;
		}

		double speed = Math.min(MAX_RISE_SPEED, 0.3 + 0.12 * remaining);
		int want = driver != null ? (int) Math.floor(risen) : rock.dy + Math.max(1, (int) Math.round(speed));
		want = Math.max(rock.dy, Math.min(rock.total, want));

		if (want != rock.dy) {
			shift(rock, want);
		}

		// riders that left the rock (stepped off, died, went away) get their gravity back
		Set<UUID> carriedIds = new HashSet<>();

		for (ServerPlayer player : carried) {
			carriedIds.add(player.getUUID());
		}

		for (UUID id : new ArrayList<>(rock.lifted)) {
			if (!carriedIds.contains(id)) {
				releaseLift(rock, id);
			}
		}

		for (ServerPlayer player : carried) {
			lift(rock, player, speed);
		}

		if (rock.age % 4 == 0) {
			double y = rock.box.minY() + rock.dy - 0.3;
			command(rock.level, String.format(Locale.ROOT, "particle minecraft:poof %.2f %.2f %.2f 0.8 0.1 0.8 0.02 5", cx, y, cz));
		}

		if (rock.age % 12 == 0) {
			command(rock.level, String.format(Locale.ROOT, "playsound minecraft:block.deepslate.hit block @a %.1f %.1f %.1f 2.0 0.5",
					cx, (double) (rock.box.minY() + rock.dy), cz));
		}
	}

	/**
	 * Holds a rider up by bending their gravity (like a geyser does): each tick the gravity is set so that their upward speed
	 * moves towards the wanted speed. Vanilla moves a player by (speed - gravity) * 0.98 each tick, so the gravity that gives a
	 * wanted next speed is (speed - wanted / 0.98).
	 */
	private static void lift(Rock rock, ServerPlayer player, double wantedSpeed) {
		AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);

		if (gravity == null) {
			return;
		}

		UUID id = player.getUUID();
		Double before = rock.lastY.put(id, player.getY());
		double speed = before == null ? 0.0 : player.getY() - before;
		double next = speed + LIFT_GAIN * (wantedSpeed - speed);
		double base = gravity.getBaseValue();
		double total = Math.max(-LIFT_MAX_THRUST, Math.min(base, speed - next / 0.98));
		gravity.addOrUpdateTransientModifier(new AttributeModifier(LIFT_ID, total - base, AttributeModifier.Operation.ADD_VALUE));
		rock.lifted.add(id);
	}

	private static void releaseLift(Rock rock, UUID id) {
		rock.lifted.remove(id);
		rock.lastY.remove(id);
		Player player = rock.level.getPlayerByUUID(id);

		if (player != null) {
			AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);

			if (gravity != null) {
				gravity.removeModifier(LIFT_ID);
			}
		}
	}

	private static void releaseLift(Rock rock) {
		for (UUID id : new ArrayList<>(rock.lifted)) {
			releaseLift(rock, id);
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

		if (rock.phase == Phase.FALLING && newDy < rock.dy) {
			crush(rock, newDy);
		}

		// leaves in the way (above when rising, below when falling) are broken as the rock moves through them
		clearLeaves(level, rock.box, rock.box.minY() + Math.min(rock.dy, newDy), rock.box.maxY() + Math.max(rock.dy, newDy) + 1);

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
