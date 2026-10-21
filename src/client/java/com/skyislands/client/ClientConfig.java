package com.skyislands.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

import com.skyislands.SkyIslands;

/** Client settings, read once at startup from config/sky_islands.properties (created with defaults if missing). */
public final class ClientConfig {
	private ClientConfig() { }

	// ---- music
	public static boolean musicEnabled = true;
	/** Sky music starts above this height... */
	public static double skyMusicEnterY = 200;
	/** ...and fades out when you drop below this one (lower than enter, so it does not flicker). */
	public static double skyMusicExitY = 185;
	public static int skyFadeInTicks = 60;
	public static int skyFadeOutTicks = 100;
	/** Silence between sky tracks (20 ticks = 1 second). */
	public static int skyMusicGapTicks = 600;
	public static int launchFadeInTicks = 10;
	public static int launchFadeOutTicks = 80;

	// ---- clouds (a copy of the original Sky Lands cloud particles)
	public static boolean cloudsEnabled = true;
	/** Chance per tick that a burst of clouds is spawned. The original used 0.35; lower is lighter on the GPU. */
	public static double cloudSpawnChance = 0.20;
	public static int cloudsPerBurst = 20;
	/** Clouds spawn within this many blocks of you (original default: 256). */
	public static int cloudSpread = 256;
	public static double cloudBaseY = 260;
	public static double cloudHeightVariation = 120;

	// ---- golden foliage on islands over temperate biomes
	public static boolean goldenFoliage = true;
	public static int goldenFoliageMinY = 120;

	// ---- orange grass on the autumn (poplar) islands
	public static boolean orangeGrass = true;
	public static int orangeGrassMinY = 150;

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

		musicEnabled = bool(p, "musicEnabled", musicEnabled);
		skyMusicEnterY = dbl(p, "skyMusicEnterY", skyMusicEnterY);
		skyMusicExitY = dbl(p, "skyMusicExitY", skyMusicExitY);
		skyFadeInTicks = integer(p, "skyFadeInTicks", skyFadeInTicks);
		skyFadeOutTicks = integer(p, "skyFadeOutTicks", skyFadeOutTicks);
		skyMusicGapTicks = integer(p, "skyMusicGapTicks", skyMusicGapTicks);
		launchFadeInTicks = integer(p, "launchFadeInTicks", launchFadeInTicks);
		launchFadeOutTicks = integer(p, "launchFadeOutTicks", launchFadeOutTicks);
		cloudsEnabled = bool(p, "cloudsEnabled", cloudsEnabled);
		cloudSpawnChance = dbl(p, "cloudSpawnChance", cloudSpawnChance);
		cloudsPerBurst = integer(p, "cloudsPerBurst", cloudsPerBurst);
		cloudSpread = integer(p, "cloudSpread", cloudSpread);
		cloudBaseY = dbl(p, "cloudBaseY", cloudBaseY);
		cloudHeightVariation = dbl(p, "cloudHeightVariation", cloudHeightVariation);
		orangeGrass = bool(p, "orangeGrass", orangeGrass);
		orangeGrassMinY = integer(p, "orangeGrassMinY", orangeGrassMinY);
		goldenFoliage = bool(p, "goldenFoliage", goldenFoliage);
		goldenFoliageMinY = integer(p, "goldenFoliageMinY", goldenFoliageMinY);

		// write the file back (adds any new keys, keeps the file self-documenting)
		Properties out = p;
		out.setProperty("musicEnabled", String.valueOf(musicEnabled));
		out.setProperty("skyMusicEnterY", String.valueOf(skyMusicEnterY));
		out.setProperty("skyMusicExitY", String.valueOf(skyMusicExitY));
		out.setProperty("skyFadeInTicks", String.valueOf(skyFadeInTicks));
		out.setProperty("skyFadeOutTicks", String.valueOf(skyFadeOutTicks));
		out.setProperty("skyMusicGapTicks", String.valueOf(skyMusicGapTicks));
		out.setProperty("launchFadeInTicks", String.valueOf(launchFadeInTicks));
		out.setProperty("launchFadeOutTicks", String.valueOf(launchFadeOutTicks));
		out.setProperty("cloudsEnabled", String.valueOf(cloudsEnabled));
		out.setProperty("cloudSpawnChance", String.valueOf(cloudSpawnChance));
		out.setProperty("cloudsPerBurst", String.valueOf(cloudsPerBurst));
		out.setProperty("cloudSpread", String.valueOf(cloudSpread));
		out.setProperty("cloudBaseY", String.valueOf(cloudBaseY));
		out.setProperty("cloudHeightVariation", String.valueOf(cloudHeightVariation));
		out.setProperty("orangeGrass", String.valueOf(orangeGrass));
		out.setProperty("orangeGrassMinY", String.valueOf(orangeGrassMinY));
		out.setProperty("goldenFoliage", String.valueOf(goldenFoliage));
		out.setProperty("goldenFoliageMinY", String.valueOf(goldenFoliageMinY));

		try (OutputStream os = Files.newOutputStream(file)) {
			out.store(os, "Sky Islands {Islands} client settings. 20 ticks = 1 second. Delete a line to restore its default.");
		} catch (IOException e) {
			SkyIslands.LOGGER.warn("Could not write {}: {}", file, e.toString());
		}
	}

	private static boolean bool(Properties p, String k, boolean d) {
		String v = p.getProperty(k);
		return v == null ? d : Boolean.parseBoolean(v.trim());
	}

	private static int integer(Properties p, String k, int d) {
		try {
			String v = p.getProperty(k);
			return v == null ? d : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return d;
		}
	}

	private static double dbl(Properties p, String k, double d) {
		try {
			String v = p.getProperty(k);
			return v == null ? d : Double.parseDouble(v.trim());
		} catch (NumberFormatException e) {
			return d;
		}
	}
}
