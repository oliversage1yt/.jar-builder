package com.skyislands.client;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;

import com.skyislands.SkyIslands;

/**
 * Silences Minecraft's own background music while a Sky Islands track is playing.
 * Uses reflection on purpose: music handling changed a lot in 26.x, and this way a renamed method can never break the build,
 * it only logs one warning and our tracks simply play alongside vanilla music.
 */
final class VanillaMusic {
	private static Method getMusicManager;
	private static Method stopPlaying;
	private static boolean failed;

	private VanillaMusic() { }

	static void stop(Minecraft mc) {
		if (failed) {
			return;
		}

		try {
			if (getMusicManager == null) {
				getMusicManager = Minecraft.class.getMethod("getMusicManager");
			}

			Object manager = getMusicManager.invoke(mc);

			if (stopPlaying == null) {
				stopPlaying = manager.getClass().getMethod("stopPlaying");
			}

			stopPlaying.invoke(manager);
		} catch (Throwable t) {
			failed = true;
			SkyIslands.LOGGER.warn("Could not silence vanilla music (Sky Islands tracks will overlap it): {}", t.toString());
		}
	}
}
