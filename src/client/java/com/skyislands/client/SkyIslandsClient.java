package com.skyislands.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import com.skyislands.SkyIslands;

public class SkyIslandsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		CloudSetup.register();
		AutumnGrassTint.register();
		GoldenFoliageTint.register();
		MusicToast.register();
		RecipeToastHud.register();
		RockHighlight.register();
		RockRecallSounds.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			MusicController.tick(client);
			CloudSpawner.tick(client);
			RainAboveClouds.tick(client);
			WindBlockInput.tick(client);
			RockHighlight.tick(client);
			RockRecallSounds.tick(client);
			PrismarineArrowHider.tick(client);
		});

		SkyIslands.LOGGER.info("Sky Islands client features ready (music fades, clouds, orange grass, golden foliage).");
	}
}
