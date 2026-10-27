package com.skyislands;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sky Islands {Islands}. Almost everything (islands, geysers, structures) is data in src/main/resources/data.
 * The code in this mod adds: no-altitude-snow (mixin), and client-side music fading, cloud particles and orange grass, plus the sky arena boss.
 */
public class SkyIslands implements ModInitializer {
	public static final String MOD_ID = "sky_islands";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommonConfig.load();
		ServerChunkEvents.CHUNK_GENERATE.register(GroundFreezer::onGenerate);
		ServerTickEvents.END_SERVER_TICK.register(GroundFreezer::tick);
		WindSpears.register();
		PrismarineItems.register();
		PrismarineBows.register();
		RecipeUnlocks.register();
		RainDrowned.register();
		SpearVisual.register();
		ArenaBoss.register();
		GlowingObsidian.register();
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			GroundFreezer.clear();
			ArenaBoss.clear();
			WindSpears.clear();
			PrismarineItems.clear();
			PrismarineBows.clear();
			RainDrowned.clear();
			com.skyislands.world.IslandColumns.clear();
		});
		LOGGER.info("Sky Islands {Islands} loaded.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
