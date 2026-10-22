package com.skyislands.client;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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

	/** Consecutive air blocks under a block that mark it as part of a floating island (mountains have no gap this tall). */
	private static final int MIN_AIR_GAP = 24;
	private static final int MAX_CACHE = 400_000;
	private static final ConcurrentHashMap<Long, Boolean> ISLAND_CACHE = new ConcurrentHashMap<>();
	private static volatile ClientLevel cachedLevel;

	private static final ColorResolver GOLDEN_RESOLVER = (biome, x, z) ->
			isGoldenBiome(biome) ? GOLD : BiomeColors.FOLIAGE_COLOR_RESOLVER.getColor(biome, x, z);

	/** Same idea for grass, grass blocks and ferns, which use the grass colour instead of the foliage colour. */
	private static final ColorResolver GOLDEN_GRASS_RESOLVER = (biome, x, z) ->
			isGoldenBiome(biome) ? GOLD : BiomeColors.GRASS_COLOR_RESOLVER.getColor(biome, x, z);

	private GoldenFoliageTint() { }

	public static void register() {
		ColorResolverRegistry.register(GOLDEN_RESOLVER);
		ColorResolverRegistry.register(GOLDEN_GRASS_RESOLVER);

		BlockTintSource source = new BlockTintSource() {
			@Override
			public int color(BlockState state) {
				return NEUTRAL;
			}

			@Override
			public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
				if (isGoldenZone(pos)) {
					return level.getBlockTint(pos, GOLDEN_RESOLVER);
				}

				return level.getBlockTint(pos, BiomeColors.FOLIAGE_COLOR_RESOLVER);
			}
		};

		BlockColorRegistry.register(List.of(source), Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES, Blocks.VINE);
	}

	/**
	 * Grass colour for grass blocks, grass and ferns: gold on golden islands, otherwise the normal biome grass colour.
	 * Used by {@link AutumnGrassTint}, which owns the grass-type blocks.
	 */
	public static int grassColor(BlockAndTintGetter level, BlockPos pos) {
		if (isGoldenZone(pos)) {
			return level.getBlockTint(pos, GOLDEN_GRASS_RESOLVER);
		}

		return level.getBlockTint(pos, BiomeColors.GRASS_COLOR_RESOLVER);
	}

	/** A block only gets the golden tint when it is high enough AND sits on a floating island rather than on a mountain. */
	private static boolean isGoldenZone(BlockPos pos) {
		return ClientConfig.goldenFoliage && pos.getY() >= ClientConfig.goldenFoliageMinY && isOnSkyIsland(pos);
	}

	/**
	 * A floating island has a tall gap of air somewhere beneath it before any ground; a mountain is solid (give or take
	 * caves) all the way down. The result is cached per column and 16-block slice, and cleared when the world changes.
	 */
	private static boolean isOnSkyIsland(BlockPos pos) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) {
			return false;
		}

		if (level != cachedLevel || ISLAND_CACHE.size() > MAX_CACHE) {
			ISLAND_CACHE.clear();
			cachedLevel = level;
		}

		long key = ((long) pos.getX() & 0x3FFFFFFL) << 38 | ((long) pos.getZ() & 0x3FFFFFFL) << 12 | ((long) (pos.getY() >> 4) & 0xFFFL);
		Boolean cached = ISLAND_CACHE.get(key);

		if (cached != null) {
			return cached;
		}

		boolean island = false;
		int run = 0;
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(pos.getX(), 0, pos.getZ());

		for (int y = pos.getY() - 1; y >= level.getMinY(); y--) {
			probe.setY(y);

			if (level.getBlockState(probe).isAir()) {
				if (++run >= MIN_AIR_GAP) {
					island = true;
					break;
				}
			} else {
				run = 0;
			}
		}

		ISLAND_CACHE.put(key, island);
		return island;
	}

	private static boolean isGoldenBiome(Biome biome) {
		Boolean cached = CACHE.get(biome);

		if (cached != null) {
			return cached;
		}

		var level = Minecraft.getInstance().level;

		if (level == null) {
			// Don't cache a guess made while no world is loaded.
			return false;
		}

		var key = level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome);
		boolean golden = key != null && GOLDEN_BIOMES.contains(key.getPath());
		CACHE.put(biome, golden);
		return golden;
	}
}
