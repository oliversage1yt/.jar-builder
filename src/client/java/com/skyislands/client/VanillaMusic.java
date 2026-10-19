package com.skyislands.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;

import com.skyislands.SkyIslands;

/**
 * Quiets Minecraft's own background music while a Sky Islands track is playing: it fades the vanilla track out when ours
 * starts, and stops any vanilla track that tries to start while ours is playing.
 *
 * <p>Uses reflection on purpose: music handling changed a lot in 26.x, and this way a renamed method or field can never
 * break the build. If something cannot be found it logs one warning and our tracks simply play alongside vanilla music
 * (or vanilla music is cut instead of faded).
 */
final class VanillaMusic {
	private static Method getMusicManager;
	private static Method stopPlaying;
	private static Field volumeField;
	private static boolean failed;
	private static boolean fadeFailed;

	private static Object fadingInstance;
	private static float fadeStartVolume;
	private static int fadeTotal;
	private static int fadeLeft;

	private VanillaMusic() { }

	/** Fade the currently playing vanilla track to silence over {@code ticks}, then stop it. */
	static void fadeOut(Minecraft mc, int ticks) {
		if (failed || fadeFailed || fadingInstance != null) {
			return;
		}

		try {
			Object instance = currentMusic(mc);

			if (instance == null) {
				return;
			}

			if (ticks <= 0 || !(instance instanceof AbstractSoundInstance)) {
				stop(mc);
				return;
			}

			if (volumeField == null) {
				volumeField = AbstractSoundInstance.class.getDeclaredField("volume");
				volumeField.setAccessible(true);
			}

			fadingInstance = instance;
			fadeStartVolume = volumeField.getFloat(instance);
			fadeTotal = ticks;
			fadeLeft = ticks;
		} catch (Throwable t) {
			fadeFailed = true;
			SkyIslands.LOGGER.warn("Could not fade vanilla music (it will be cut instead): {}", t.toString());
			stop(mc);
		}
	}

	/** Call every client tick to advance a running fade. */
	static void tick(Minecraft mc) {
		if (fadingInstance == null) {
			return;
		}

		try {
			fadeLeft--;

			if (fadeLeft <= 0) {
				fadingInstance = null;
				stop(mc);
				return;
			}

			volumeField.setFloat(fadingInstance, fadeStartVolume * fadeLeft / fadeTotal);
		} catch (Throwable t) {
			fadingInstance = null;
			fadeFailed = true;
			stop(mc);
		}
	}

	/** Called regularly while our music plays: stop any vanilla track that started (unless one is already fading out). */
	static void silence(Minecraft mc) {
		if (fadingInstance != null) {
			return;
		}

		stop(mc);
	}

	/** Cut the vanilla music immediately. */
	static void stop(Minecraft mc) {
		if (failed) {
			return;
		}

		try {
			Object manager = manager(mc);

			if (stopPlaying == null) {
				stopPlaying = manager.getClass().getMethod("stopPlaying");
			}

			stopPlaying.invoke(manager);
		} catch (Throwable t) {
			failed = true;
			SkyIslands.LOGGER.warn("Could not silence vanilla music (Sky Islands tracks will overlap it): {}", t.toString());
		}
	}

	private static Object manager(Minecraft mc) throws ReflectiveOperationException {
		if (getMusicManager == null) {
			getMusicManager = Minecraft.class.getMethod("getMusicManager");
		}

		return getMusicManager.invoke(mc);
	}

	/** The sound instance the music manager is currently playing, or null. Found by field type, so the field name does not matter. */
	private static Object currentMusic(Minecraft mc) throws ReflectiveOperationException {
		Object manager = manager(mc);

		for (Field field : manager.getClass().getDeclaredFields()) {
			if (SoundInstance.class.isAssignableFrom(field.getType())) {
				field.setAccessible(true);
				Object value = field.get(manager);

				if (value != null) {
					return value;
				}
			}
		}

		return null;
	}
}
