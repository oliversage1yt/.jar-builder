package com.skyislands.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * Copy of the original Sky Lands cloud spawner: every client tick, with some probability, a burst of cloud particles
 * appears at a random spot near the player, at a height that follows slow, wide noise (so the cloud deck gently rolls
 * up and down). The original ran 35% of ticks with 19-20 particles per burst.
 */
public final class CloudSpawner {
	private static final float DRIFT_X = 0.1F;
	private static final float DRIFT_Z = 0.06F;

	private CloudSpawner() { }

	public static void tick(Minecraft mc) {
		if (!ClientConfig.cloudsEnabled) {
			return;
		}

		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;

		if (player == null || level == null || !Level.OVERWORLD.equals(level.dimension())) {
			return;
		}

		RandomSource random = level.getRandom();

		if (random.nextDouble() >= ClientConfig.cloudSpawnChance) {
			return;
		}

		double spread = ClientConfig.cloudSpread * 0.75;
		double cx = player.getX() + Mth.randomBetween(random, (float) -spread, (float) spread);
		double cz = player.getZ() + Mth.randomBetween(random, (float) -spread, (float) spread);
		double cy = ClientConfig.cloudBaseY + noise(cx, cz) * ClientConfig.cloudHeightVariation;

		for (int i = 0; i < ClientConfig.cloudsPerBurst; i++) {
			double x = cx + Mth.randomBetween(random, -35.0F, 35.0F);
			double y = cy + Mth.randomBetween(random, -22.0F, 22.0F);
			double z = cz + Mth.randomBetween(random, -35.0F, 35.0F);
			level.addParticle(CloudSetup.CLOUD, x, y, z, DRIFT_X, 0.0, DRIFT_Z);
		}
	}

	/** Smooth, slowly varying value in [-1, 1] (a stand-in for the original's very low frequency simplex noise). */
	private static double noise(double x, double z) {
		return 0.5 * Math.sin(x / 9000.0 + 1.3 * Math.sin(z / 13000.0)) + 0.5 * Math.sin(z / 11000.0 + 0.7 * Math.cos(x / 15000.0));
	}
}
