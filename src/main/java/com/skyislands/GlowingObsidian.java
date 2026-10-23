package com.skyislands;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The red "glowing obsidian" centre of the sky arena.
 *
 * <p>A Crying Obsidian that sits in the middle of a flat 3x3 pattern (Blackstone in the four corners, Obsidian on the four
 * sides) is swapped for {@code sky_islands:glowing_obsidian}: the same block as Crying Obsidian (same strength, light and
 * drops) with a red texture. If the pattern is broken it turns back into Crying Obsidian. Loaded chunks near players are
 * checked once a second, which covers generated arenas and patterns that players build themselves.
 */
public final class GlowingObsidian {
	public static Block BLOCK;

	/** Chunks around each player that are checked. */
	private static final int SCAN_CHUNK_RADIUS = 4;
	private static int tickCounter;

	private GlowingObsidian() { }

	public static void register() {
		Identifier id = SkyIslands.id("glowing_obsidian");
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
		BLOCK = Registry.register(BuiltInRegistries.BLOCK, id,
				new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.CRYING_OBSIDIAN).setId(key)));
		ServerTickEvents.END_SERVER_TICK.register(GlowingObsidian::tick);
	}

	private static boolean isCentre(BlockState state) {
		return state.is(Blocks.CRYING_OBSIDIAN) || state.is(BLOCK);
	}

	private static void tick(MinecraftServer server) {
		if (BLOCK == null || ++tickCounter % 20 != 0) {
			return;
		}

		for (ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer player : level.players()) {
				int pcx = player.blockPosition().getX() >> 4;
				int pcz = player.blockPosition().getZ() >> 4;

				for (int dx = -SCAN_CHUNK_RADIUS; dx <= SCAN_CHUNK_RADIUS; dx++) {
					for (int dz = -SCAN_CHUNK_RADIUS; dz <= SCAN_CHUNK_RADIUS; dz++) {
						BlockPos corner = new BlockPos((pcx + dx) << 4, 64, (pcz + dz) << 4);

						if (level.hasChunkAt(corner)) {
							scanChunk(level, level.getChunkAt(corner));
						}
					}
				}
			}
		}
	}

	private static void scanChunk(ServerLevel level, LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];

			// Cheap palette check first: almost every section has neither block and is skipped.
			if (section == null || section.hasOnlyAir() || !section.maybeHas(GlowingObsidian::isCentre)) {
				continue;
			}

			int baseY = chunk.getSectionYFromSectionIndex(i) << 4;

			for (int x = 0; x < 16; x++) {
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						BlockState state = section.getBlockState(x, y, z);

						if (!isCentre(state)) {
							continue;
						}

						BlockPos pos = new BlockPos(minX + x, baseY + y, minZ + z);
						int pattern = patternAt(level, pos);

						if (pattern > 0 && state.is(Blocks.CRYING_OBSIDIAN)) {
							level.setBlock(pos, BLOCK.defaultBlockState(), 3);
						} else if (pattern == 0 && state.is(BLOCK)) {
							level.setBlock(pos, Blocks.CRYING_OBSIDIAN.defaultBlockState(), 3);
						}
					}
				}
			}
		}
	}

	/** 1 = the pattern is complete, 0 = it is not, -1 = part of it is in an unloaded chunk (leave the block alone). */
	private static int patternAt(ServerLevel level, BlockPos centre) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}

				BlockPos pos = centre.offset(dx, 0, dz);

				if (!level.hasChunkAt(pos)) {
					return -1;
				}

				boolean corner = dx != 0 && dz != 0;
				BlockState state = level.getBlockState(pos);

				if (!(corner ? state.is(Blocks.BLACKSTONE) : state.is(Blocks.OBSIDIAN))) {
					return 0;
				}
			}
		}

		return 1;
	}
}
