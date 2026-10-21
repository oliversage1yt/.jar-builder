package com.skyislands.client;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes grass on the autumn (poplar) islands the dappled forest's orange, whatever biome the island floats over.
 * A grass block counts as "autumn" when it is high up and there is an autumn block nearby (poplar, fallen leaves, red shrubs, red sand, roses); everywhere else the
 * golden-island or normal biome grass colour is used (see {@link GoldenFoliageTint}), so ground-level grass is untouched.
 */
public final class AutumnGrassTint {
	/** Dappled forest grass colour (#df6827). */
	private static final int ORANGE = 0xFFDF6827;
	/** Colour used where there is no world position (item icons, particles). */
	private static final int NEUTRAL = 0xFF91BD59;

	private static final ConcurrentHashMap<Block, Boolean> MARKERS = new ConcurrentHashMap<>();
	/** Probe spacing: close enough that no patch of island grass is missed, and never more than 6 blocks away (safe inside the chunk-mesh region). */
	private static final int[] OFFSET_XZ = {0, -2, 2, -4, 4, -6, 6};
	/** Fallen leaves, shrubs and roses sit just above the grass; poplar trunks and leaves reach higher. */
	private static final int[] OFFSET_Y = {1, 2, 4, 7};

	private AutumnGrassTint() { }

	public static void register() {
		BlockTintSource source = new BlockTintSource() {
			@Override
			public int color(BlockState state) {
				return NEUTRAL;
			}

			@Override
			public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
				if (ClientConfig.orangeGrass && pos.getY() >= ClientConfig.orangeGrassMinY && nearAutumnBlock(level, pos)) {
					return ORANGE;
				}

				// Not an autumn island: golden islands get gold grass to match their leaves, everything else is vanilla.
				return GoldenFoliageTint.grassColor(level, pos);
			}
		};

		BlockColorRegistry.register(List.of(source), Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN);
	}

	private static boolean nearAutumnBlock(BlockAndTintGetter level, BlockPos pos) {
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

		for (int dy : OFFSET_Y) {
			for (int dx : OFFSET_XZ) {
				for (int dz : OFFSET_XZ) {
					probe.setWithOffset(pos, dx, dy, dz);

					if (isMarker(level.getBlockState(probe).getBlock())) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/** Blocks that only appear on the autumn islands (poplar wood/leaves, fallen leaves, red shrubs, red sand, rose bushes). */
	private static boolean isMarker(Block block) {
		return MARKERS.computeIfAbsent(block, b -> {
			String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
			return path.contains("poplar") || path.equals("leaf_litter") || path.equals("red_shrub") || path.equals("rose_bush") || path.startsWith("red_sand");
		});
	}
}
