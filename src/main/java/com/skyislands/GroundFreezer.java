package com.skyislands;

import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Vanilla puts snow and ice on whatever the "highest block" heightmap says is the surface. Over an island that is the
 * island's top, so the ground (and any river or lake) underneath never freezes and shows bare patches in snowy biomes.
 *
 * <p>After a chunk is generated this looks for columns that have an island floating well above the real ground, and
 * applies the same snow layer / ice that vanilla would have applied at ground level.
 */
public final class GroundFreezer {
	/** Islands never start below this height, so columns whose highest block is lower are left to vanilla. */
	private static final int MIN_ISLAND_Y = 110;
	/** Highest block must be this far above the terrain before we treat the column as "island above" (tall trees stay below). */
	private static final int MIN_GAP = 40;

	private static boolean disabled;
	private static Method coldEnoughToSnow;
	private static boolean coldHasSeaLevel;
	private static boolean coldLookupFailed;

	private GroundFreezer() { }

	public static void onGenerate(ServerLevel level, LevelChunk chunk) {
		if (disabled) {
			return;
		}

		try {
			freeze(level, chunk);
		} catch (Throwable t) {
			disabled = true;
			SkyIslands.LOGGER.error("Sky Islands ground freezing failed and was disabled for this session", t);
		}
	}

	private static void freeze(ServerLevel level, LevelChunk chunk) {
		ServerChunkCache source = level.getChunkSource();
		var generator = source.getGenerator();
		var randomState = source.randomState();
		var chunkPos = chunk.getPos();

		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);

				if (top < MIN_ISLAND_Y) {
					continue;
				}

				int x = chunkPos.getMinBlockX() + lx;
				int z = chunkPos.getMinBlockZ() + lz;
				int ground = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);

				if (top - ground < MIN_GAP) {
					continue;
				}

				BlockPos pos = new BlockPos(x, ground, z);

				if (!level.getBlockState(pos).isAir()) {
					continue;
				}

				Biome biome = level.getBiome(pos).value();

				if (!isCold(biome, pos)) {
					continue;
				}

				BlockPos below = pos.below();
				BlockState belowState = level.getBlockState(below);

				if (belowState.is(Blocks.WATER)) {
					if (belowState.getFluidState().isSource()) {
						level.setBlock(below, Blocks.ICE.defaultBlockState(), 2);
					}
				} else {
					BlockState snow = Blocks.SNOW.defaultBlockState();

					if (snow.canSurvive(level, pos)) {
						level.setBlock(pos, snow, 2);

						if (belowState.hasProperty(BlockStateProperties.SNOWY)) {
							level.setBlock(below, belowState.setValue(BlockStateProperties.SNOWY, true), 2);
						}
					}
				}
			}
		}
	}

	/** Asks the biome itself whether snow forms here. Reflection, so a changed signature can only disable this, never break the build. */
	private static boolean isCold(Biome biome, BlockPos pos) {
		if (coldLookupFailed) {
			return false;
		}

		try {
			if (coldEnoughToSnow == null) {
				try {
					coldEnoughToSnow = Biome.class.getDeclaredMethod("coldEnoughToSnow", BlockPos.class, int.class);
					coldHasSeaLevel = true;
				} catch (NoSuchMethodException e) {
					coldEnoughToSnow = Biome.class.getDeclaredMethod("coldEnoughToSnow", BlockPos.class);
					coldHasSeaLevel = false;
				}

				coldEnoughToSnow.setAccessible(true);
			}

			Object result = coldHasSeaLevel ? coldEnoughToSnow.invoke(biome, pos, 63) : coldEnoughToSnow.invoke(biome, pos);
			return Boolean.TRUE.equals(result);
		} catch (Throwable t) {
			coldLookupFailed = true;
			SkyIslands.LOGGER.warn("Could not ask biomes whether it is cold (no snow/ice under islands): {}", t.toString());
			return false;
		}
	}
}
