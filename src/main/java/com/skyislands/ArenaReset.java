package com.skyislands;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Puts the lower part of the boss arena back the way it generated, every time the boss is respawned.
 *
 * <p>"Lower part" is the fighting pit: everything from the bottom of the arena up to {@link #LOWER_TOP_ABOVE_ALTAR} blocks
 * above the altar (the floor the boss stands on). Everything above that (the roof ring and towers) is never touched, so
 * blocks players place or break up there stay as they are.
 *
 * <p>The reference is the arena's own structure file ({@link ArenaTemplate}). The arena was placed with a random rotation,
 * so the job first tries all four rotations around the altar and keeps the one that matches the world best; if even the
 * best one matches too little (e.g. a player built their own altar somewhere), nothing is touched. Cells that already
 * match are skipped, so the cost is proportional to how much the players changed. The work is spread over several ticks
 * (bottom to top) and finished in one go if the ritual ends early.
 */
final class ArenaReset {
	/** The lower part reaches this many blocks above the altar block (the roof ring of the arena starts one block higher). */
	static final int LOWER_TOP_ABOVE_ALTAR = 5;

	/** Cells looked at, and blocks changed, per tick while the reset is spread out. */
	private static final int EXAMINE_PER_TICK = 1500;
	private static final int CHANGES_PER_TICK = 400;
	/** At least this share of the arena's solid blocks must be in place for it to count as the arena. */
	private static final double MIN_MATCH = 0.40;
	/** Players are kept out of solid blocks within this many blocks (horizontally) of the altar. */
	private static final int RESCUE_RADIUS = 20;

	private static final Rotation[] ROTATIONS = {
		Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90
	};

	// ---- the lower part of the template, bottom layer first (shared by every reset)
	private static boolean loaded;
	private static boolean failed;
	private static ArenaTemplate template;
	private static int[] cx;
	private static int[] cy;
	private static int[] cz;
	private static int[] cs;
	private static int cells;
	private static int solidCells;

	private final ServerLevel level;
	private final BlockPos altar;
	private final int rotation;
	private final BlockState[] states;
	private int cursor;
	private int changed;
	private boolean done;

	private ArenaReset(ServerLevel level, BlockPos altar, int rotation, BlockState[] states) {
		this.level = level;
		this.altar = altar;
		this.rotation = rotation;
		this.states = states;
	}

	/** Starts a reset around the altar, or returns null when there is nothing to reset (disabled, not an arena, not loaded). */
	static ArenaReset begin(ServerLevel level, BlockPos altar) {
		if (!CommonConfig.arenaResetOnRespawn || !ensureLoaded()) {
			return null;
		}

		// All chunks the arena touches must be loaded, or part of it would be left alone.
		int radius = maxRadius();

		for (int chunkX = (altar.getX() - radius) >> 4; chunkX <= (altar.getX() + radius) >> 4; chunkX++) {
			for (int chunkZ = (altar.getZ() - radius) >> 4; chunkZ <= (altar.getZ() + radius) >> 4; chunkZ++) {
				if (!level.hasChunkAt(new BlockPos(chunkX << 4, altar.getY(), chunkZ << 4))) {
					return null;
				}
			}
		}

		BlockState[] base = new BlockState[template.palette.length];

		for (int i = 0; i < base.length; i++) {
			base[i] = parse(template.palette[i]);
		}

		int best = -1;
		double bestShare = 0.0;

		for (int r = 0; r < ROTATIONS.length; r++) {
			int hits = 0;

			for (int i = 0; i < cells; i++) {
				BlockState want = base[cs[i]];

				if (want == null || want.isAir() || (cx[i] == 0 && cy[i] == 0 && cz[i] == 0)) {
					continue;
				}

				BlockPos pos = at(altar, r, cx[i], cy[i], cz[i]);

				if (level.getBlockState(pos).getBlock() == want.getBlock()) {
					hits++;
				}
			}

			double share = solidCells == 0 ? 0.0 : (double) hits / solidCells;

			if (share > bestShare) {
				bestShare = share;
				best = r;
			}
		}

		if (best < 0 || bestShare < MIN_MATCH) {
			SkyIslands.LOGGER.info("Arena reset skipped at {}: no arena layout found around the altar (best match {}%)", altar, Math.round(bestShare * 100.0));
			return null;
		}

		BlockState[] rotated = new BlockState[base.length];

		for (int i = 0; i < base.length; i++) {
			rotated[i] = base[i] == null ? null : base[i].rotate(ROTATIONS[best]);
		}

		return new ArenaReset(level, altar.immutable(), best, rotated);
	}

	/** Restores a slice of the arena. Returns true once everything has been checked. */
	boolean step() {
		return run(EXAMINE_PER_TICK, CHANGES_PER_TICK);
	}

	/** Restores whatever is left right now. */
	void finish() {
		run(Integer.MAX_VALUE, Integer.MAX_VALUE);

		if (changed > 0) {
			SkyIslands.LOGGER.info("Arena reset at {}: {} blocks put back", altar, changed);
			changed = -1; // logged once
		}
	}

	private boolean run(int examineBudget, int changeBudget) {
		if (done) {
			return true;
		}

		int examined = 0;
		int changesNow = 0;

		while (cursor < cells && examined < examineBudget && changesNow < changeBudget) {
			int i = cursor++;
			examined++;

			if (cx[i] == 0 && cy[i] == 0 && cz[i] == 0) {
				continue; // the altar itself (it is the glowing variant of the template's Crying Obsidian)
			}

			BlockState want = states[cs[i]];

			if (want == null) {
				continue;
			}

			BlockPos pos = at(altar, rotation, cx[i], cy[i], cz[i]);
			BlockState have = level.getBlockState(pos);

			if (have == want) {
				continue;
			}

			if (level.getBlockEntity(pos) != null) {
				// Something with an inventory (a chest a player put there): break it so its contents drop instead of vanishing.
				ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT, "setblock %d %d %d minecraft:air destroy", pos.getX(), pos.getY(), pos.getZ()));
			}

			boolean fluids = !have.getFluidState().isEmpty() || !want.getFluidState().isEmpty();
			// 3 lets water and lava notice that they lost their source; 18 changes the block without a storm of neighbour updates.
			level.setBlock(pos, want, fluids ? 3 : 18);
			changed++;
			changesNow++;

			if (changed % 12 == 0) {
				level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0.02);
			}
		}

		if (changesNow > 0) {
			rescuePlayers();
		}

		if (cursor >= cells) {
			done = true;
		}

		return done;
	}

	/** A block that came back where a player is standing would suffocate them: lift them to the first free spot above. */
	private void rescuePlayers() {
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}

			BlockPos feet = player.blockPosition();

			if (Math.abs(feet.getX() - altar.getX()) > RESCUE_RADIUS || Math.abs(feet.getZ() - altar.getZ()) > RESCUE_RADIUS) {
				continue;
			}

			if (!blocked(feet) && !blocked(feet.above())) {
				continue;
			}

			int y = feet.getY();

			for (int i = 0; i < 64; i++) {
				y++;
				BlockPos candidate = new BlockPos(feet.getX(), y, feet.getZ());

				if (!blocked(candidate) && !blocked(candidate.above())) {
					break;
				}
			}

			ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT, "tp %s %.2f %d %.2f", player.getUUID(), player.getX(), y, player.getZ()));
		}
	}

	private boolean blocked(BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	// ------------------------------------------------------------------ geometry

	/** The world position of a template cell (relative to the altar) when the arena was placed with the given rotation. */
	private static BlockPos at(BlockPos altar, int rotation, int dx, int dy, int dz) {
		int x;
		int z;

		switch (rotation) {
			case 1: // clockwise 90
				x = -dz;
				z = dx;
				break;
			case 2:
				x = -dx;
				z = -dz;
				break;
			case 3: // counter-clockwise 90
				x = dz;
				z = -dx;
				break;
			default:
				x = dx;
				z = dz;
				break;
		}

		return new BlockPos(altar.getX() + x, altar.getY() + dy, altar.getZ() + z);
	}

	private static int maxRadius() {
		int max = 0;

		for (int i = 0; i < cells; i++) {
			max = Math.max(max, Math.max(Math.abs(cx[i]), Math.abs(cz[i])));
		}

		return max;
	}

	// ------------------------------------------------------------------ template + block states

	private static synchronized boolean ensureLoaded() {
		if (loaded) {
			return true;
		}

		if (failed) {
			return false;
		}

		try {
			ArenaTemplate t = ArenaTemplate.load();
			int total = t.size();
			int count = 0;

			for (int i = 0; i < total; i++) {
				if (t.dy[i] <= LOWER_TOP_ABOVE_ALTAR) {
					count++;
				}
			}

			// Bottom layer first, so the restoring sweeps upwards: sort by (y, x, z), with the cell's index packed in the low bits.
			long[] keys = new long[count];
			int k = 0;

			for (int i = 0; i < total; i++) {
				if (t.dy[i] <= LOWER_TOP_ABOVE_ALTAR) {
					keys[k++] = ((long) (t.dy[i] + 64) << 48) | ((long) (t.dx[i] + 64) << 40) | ((long) (t.dz[i] + 64) << 32) | i;
				}
			}

			Arrays.sort(keys);
			cx = new int[count];
			cy = new int[count];
			cz = new int[count];
			cs = new int[count];

			for (int i = 0; i < count; i++) {
				int src = (int) (keys[i] & 0xFFFFFFFFL);
				cx[i] = t.dx[src];
				cy[i] = t.dy[src];
				cz[i] = t.dz[src];
				cs[i] = t.state[src];
			}

			cells = count;
			template = t;

			int solid = 0;

			for (int i = 0; i < count; i++) {
				String s = t.palette[cs[i]];

				if (!s.equals("minecraft:air") && !(cx[i] == 0 && cy[i] == 0 && cz[i] == 0)) {
					solid++;
				}
			}

			solidCells = solid;
			loaded = true;
			return true;
		} catch (IOException | RuntimeException e) {
			failed = true;
			SkyIslands.LOGGER.error("Sky Islands could not read the arena layout; the arena will not be reset on respawn", e);
			return false;
		}
	}

	/** Turns a state string such as {@code minecraft:tuff_brick_stairs[facing=north,half=bottom]} into a block state (null if unknown). */
	private static BlockState parse(String text) {
		try {
			int bracket = text.indexOf('[');
			String name = bracket < 0 ? text : text.substring(0, bracket);
			int colon = name.indexOf(':');
			Identifier id = colon < 0 ? Identifier.fromNamespaceAndPath("minecraft", name) : Identifier.fromNamespaceAndPath(name.substring(0, colon), name.substring(colon + 1));
			Block block = BuiltInRegistries.BLOCK.getValue(id);

			if (block == Blocks.AIR && !name.equals("minecraft:air")) {
				return null; // a block this version does not have
			}

			BlockState state = block.defaultBlockState();

			if (bracket >= 0) {
				for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
					int eq = pair.indexOf('=');

					if (eq > 0) {
						Property<?> property = block.getStateDefinition().getProperty(pair.substring(0, eq));

						if (property != null) {
							state = withValue(state, property, pair.substring(eq + 1));
						}
					}
				}
			}

			return state;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
		Optional<T> parsed = property.getValue(value);
		return parsed.isPresent() ? state.setValue(property, parsed.get()) : state;
	}
}
