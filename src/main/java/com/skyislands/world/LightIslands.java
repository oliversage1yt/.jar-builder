package com.skyislands.world;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import com.skyislands.Tuning;

/**
 * Finds "island above ground" in a chunk column: a stack of blocks floating above a big air gap, with ground (or water,
 * or another island) below the gap. Used by the sky light fix and by the map fix.
 */
public final class LightIslands {
	private LightIslands() { }

	/**
	 * Scans one column (local x/z, 0-15) from the top of the world down and finds the lowest air gap that is at least
	 * {@link Tuning#ISLAND_GAP} tall and sits at or above {@link Tuning#ISLAND_MIN_Y}.
	 *
	 * @param out filled on success: out[0] = lowest block of the island above the gap, out[1] = top block below the gap
	 * @return true if the column has an island above a gap
	 */
	public static boolean scan(ChunkAccess chunk, int x, int z, int[] out) {
		int minY = chunk.getMinY();
		int sections = chunk.getSectionsCount();
		boolean seen = false;
		int lastSolid = 0;
		int airRun = 0;
		boolean found = false;
		int chainBottom = 0;
		int groundTop = 0;

		for (int index = sections - 1; index >= 0; index--) {
			LevelChunkSection section = chunk.getSection(index);
			int baseY = minY + (index << 4);

			if (section.hasOnlyAir()) {
				airRun += 16;
				continue;
			}

			for (int dy = 15; dy >= 0; dy--) {
				BlockState state = section.getBlockState(x, dy, z);

				if (state.isAir()) {
					airRun++;
					continue;
				}

				int y = baseY + dy;

				if (seen && airRun >= Tuning.ISLAND_GAP && lastSolid >= Tuning.ISLAND_MIN_Y) {
					found = true;
					chainBottom = lastSolid;
					groundTop = y;
				}

				airRun = 0;
				lastSolid = y;
				seen = true;
			}
		}

		out[0] = chainBottom;
		out[1] = groundTop;
		return found;
	}
}
