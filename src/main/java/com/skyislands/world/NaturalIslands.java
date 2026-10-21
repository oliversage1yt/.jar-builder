package com.skyislands.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

import com.skyislands.SkyIslands;
import com.skyislands.Tuning;

/**
 * "Is this position inside a generated island?" Answered from the structure pieces the world generator placed
 * (tag sky_islands:island_structures). Blocks a player adds inside an island's area count as part of the island.
 */
public final class NaturalIslands {
	private static final TagKey<Structure> ISLANDS = TagKey.create(Registries.STRUCTURE, SkyIslands.id("island_structures"));

	private NaturalIslands() { }

	public static boolean isInside(ServerLevel level, BlockPos pos) {
		if (pos.getY() < Tuning.ISLAND_MIN_Y - 10) {
			return false;
		}

		return level.structureManager().getStructureWithPieceAt(pos, ISLANDS).isValid();
	}
}
