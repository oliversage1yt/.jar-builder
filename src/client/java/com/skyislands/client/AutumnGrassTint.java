package com.skyislands.client;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes grass on the autumn (poplar) islands the dappled forest's orange, whatever biome the island floats over.
 * A grass block counts as "autumn" when it is high up and there is poplar wood or leaves nearby; everywhere else the
 * normal biome grass colour is used, so ground-level grass is untouched.
 */
public final class AutumnGrassTint {
	/** Dappled forest grass colour (#df6827). */
	private static final int ORANGE = 0xFFDF6827;
	/** Colour used where there is no world position (item icons, particles). */
	private static final int NEUTRAL = 0xFF91BD59;

	private static final ConcurrentHashMap<Block, Boolean> POPLAR = new ConcurrentHashMap<>();
	private static final int[] OFFSET_XZ = {0, -4, 4, -8, 8};
	private static final int[] OFFSET_Y = {1, 4, 8};

	private AutumnGrassTint() { }

	public static void register() {
		BlockTintSource source = new BlockTintSource() {
			@Override
			public int color(BlockState state) {
				return NEUTRAL;
			}

			@Override
			public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
				if (ClientConfig.orangeGrass && pos.getY() >= ClientConfig.orangeGrassMinY && nearPoplar(level, pos)) {
					return ORANGE;
				}

				return level.getBlockTint(pos, BiomeColors.GRASS_COLOR_RESOLVER);
			}
		};

		BlockColorRegistry.register(List.of(source), Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN);
	}

	private static boolean nearPoplar(BlockAndTintGetter level, BlockPos pos) {
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

		for (int dy : OFFSET_Y) {
			for (int dx : OFFSET_XZ) {
				for (int dz : OFFSET_XZ) {
					probe.setWithOffset(pos, dx, dy, dz);

					if (isPoplar(level.getBlockState(probe).getBlock())) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private static boolean isPoplar(Block block) {
		return POPLAR.computeIfAbsent(block, b -> BuiltInRegistries.BLOCK.getKey(b).getPath().contains("poplar"));
	}
}
