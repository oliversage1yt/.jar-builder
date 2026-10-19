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

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			MusicController.tick(client);
			CloudSpawner.tick(client);
		});

		SkyIslands.LOGGER.info("Sky Islands client features ready (music fades, clouds, orange grass).");
	}
}
