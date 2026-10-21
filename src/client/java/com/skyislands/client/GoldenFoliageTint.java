package com.skyislands.client;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Golden-yellow foliage (like the islands in Tears of the Kingdom) on islands over temperate biomes: plains, forests,
 * taiga, rivers and cooler oceans. Warm or wet biomes (jungles, swamps, warm oceans), deserts, snowy biomes and so on
 * keep their normal foliage colour, and so does everything below the island band.
 */
public final class GoldenFoliageTint {
	/** Golden yellow (#e5b93b). */
	private static final int GOLD = 0xFFE5B93B;
	/** Colour used where there is no world position (item icons). */
	private static final int NEUTRAL = 0xFF48B518;

	private static final Set<String> GOLDEN_BIOMES = Set.of(
			"plains", "sunflower_plains", "forest", "flower_forest", "birch_forest", "old_growth_birch_forest", "dark_forest",
			"taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "river", "beach", "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean");

	private static final ConcurrentHashMap<Biome, Boolean> CACHE = new ConcurrentHashMap<>();

	private static final ColorResolver GOLDEN_RESOLVER = (biome, x, z) ->
			isGoldenBiome(biome) ? GOLD : BiomeColors.FOLIAGE_COLOR_RESOLVER.getColor(biome, x, z);

	private GoldenFoliageTint() { }

	public static void register() {
		ColorResolverRegistry.register(GOLDEN_RESOLVER);

		BlockTintSource source = new BlockTintSource() {
			@Override
			public int color(BlockState state) {
				return NEUTRAL;
			}

			@Override
			public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
				if (ClientConfig.goldenFoliage && pos.getY() >= ClientConfig.goldenFoliageMinY) {
					return level.getBlockTint(pos, GOLDEN_RESOLVER);
				}

				return level.getBlockTint(pos, BiomeColors.FOLIAGE_COLOR_RESOLVER);
			}
		};

		BlockColorRegistry.register(List.of(source), Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES, Blocks.VINE);
	}

	private static boolean isGoldenBiome(Biome biome) {
		return CACHE.computeIfAbsent(biome, b -> {
			var level = Minecraft.getInstance().level;

			if (level == null) {
				return false;
			}

			var key = level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(b);
			return key != null && GOLDEN_BIOMES.contains(key.getPath());
		});
	}
}
