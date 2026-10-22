package com.skyislands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings that matter on the server (and so apply in single player and on dedicated servers).
 * Stored in config/sky_islands.properties next to the client settings; each side only touches its own keys.
 */
public final class CommonConfig {
	private CommonConfig() { }

	/** Sky light passes through islands, so the ground under them is not in shadow. Interiors of island buildings are bright too. */
	public static boolean lightThroughIslands = true;
	/** Maps made below the islands ignore them; maps first used in the sky show only the islands (plus sky blue). */
	public static boolean skyMaps = true;
	/** Sand, gravel and similar blocks that fall from naturally generated islands vanish below the island band instead of piling up. */
	public static boolean naturalFallingBlocksVanish = true;
	/** Water and lava of naturally generated islands cannot flow out of the island. */
	public static boolean naturalFluidsContained = true;

	/** Maximum health of the sky arena boss (a vanilla Illusioner has 32). */
	public static double arenaBossHealth = 200.0;

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("sky_islands.properties");
		Properties p = new Properties();

		if (Files.exists(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				p.load(in);
			} catch (IOException e) {
				SkyIslands.LOGGER.warn("Could not read {}: {}", file, e.toString());
			}
		}

		lightThroughIslands = bool(p, "lightThroughIslands", lightThroughIslands);
		skyMaps = bool(p, "skyMaps", skyMaps);
		naturalFallingBlocksVanish = bool(p, "naturalFallingBlocksVanish", naturalFallingBlocksVanish);
		naturalFluidsContained = bool(p, "naturalFluidsContained", naturalFluidsContained);

		arenaBossHealth = Math.max(1.0, dbl(p, "arenaBossHealth", arenaBossHealth));

		p.setProperty("arenaBossHealth", String.valueOf(arenaBossHealth));
		p.setProperty("lightThroughIslands", String.valueOf(lightThroughIslands));
		p.setProperty("skyMaps", String.valueOf(skyMaps));
		p.setProperty("naturalFallingBlocksVanish", String.valueOf(naturalFallingBlocksVanish));
		p.setProperty("naturalFluidsContained", String.valueOf(naturalFluidsContained));

		try {
			Files.createDirectories(file.getParent());

			try (OutputStream os = Files.newOutputStream(file)) {
				p.store(os, "Sky Islands {Islands} settings. 20 ticks = 1 second. Delete a line to restore its default.");
			}
		} catch (IOException e) {
			SkyIslands.LOGGER.warn("Could not write {}: {}", file, e.toString());
		}
	}

	private static double dbl(Properties p, String key, double fallback) {
		String v = p.getProperty(key);

		if (v == null) {
			return fallback;
		}

		try {
			return Double.parseDouble(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean bool(Properties p, String key, boolean fallback) {
		String v = p.getProperty(key);
		return v == null ? fallback : Boolean.parseBoolean(v.trim());
	}
}
