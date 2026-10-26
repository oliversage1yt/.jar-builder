package com.skyislands.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import com.skyislands.Tuning;
import com.skyislands.world.CloudDeck;

/**
 * No rain above the clouds: while you are above the cloud deck the level's rain strength shown on your screen is faded
 * to nothing (no rain, no rain sounds, no rain-dark sky), and it fades back in as you descend into the clouds.
 *
 * <p>The server only sends the real rain strength when it changes, so the real value is remembered here: whenever the
 * level's value is not the one this class last set, the server has just sent a new real value.
 */
public final class RainAboveClouds {
	private static ClientLevel lastLevel;
	private static float lastSet = -1.0F;
	private static float real;

	/** Set by RainLevelMixin once it is scaling the rain strength itself, which makes the per-tick fallback below unnecessary. */
	public static volatile boolean mixinActive;

	private RainAboveClouds() { }

	/** How much of the rain shows: 0 above the cloud deck, 1 below it, fading in between (1 when the setting is off). */
	public static float factor(ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;

		if (player == null || mc.level != level || !ClientConfig.hideRainAboveClouds || !Level.OVERWORLD.equals(level.dimension())) {
			return 1.0F;
		}

		double cloudY = CloudDeck.heightAt(ClientConfig.cloudBaseY, ClientConfig.cloudHeightVariation, player.getX(), player.getZ());
		double half = Tuning.CLOUD_HALF_THICKNESS;
		return (float) Mth.clamp((cloudY + half - player.getEyeY()) / (2.0 * half), 0.0, 1.0);
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;

		if (level != lastLevel) {
			lastLevel = level;
			lastSet = -1.0F;
		}

		if (level == null || player == null) {
			return;
		}

		if (!mixinActive) {
			level.getRainLevel(1.0F); // probe: the mixin flags itself the first time it runs
		}

		if (mixinActive) {
			return; // the mixin scales the rain when it is read; nothing is written to the level
		}

		if (!ClientConfig.hideRainAboveClouds || !Level.OVERWORLD.equals(level.dimension())) {
			restore(level);
			return;
		}

		float current = level.getRainLevel(1.0F);

		if (lastSet < 0.0F || Math.abs(current - lastSet) > 0.002F) {
			real = current;
		}

		double cloudY = CloudDeck.heightAt(ClientConfig.cloudBaseY, ClientConfig.cloudHeightVariation, player.getX(), player.getZ());
		double half = Tuning.CLOUD_HALF_THICKNESS;
		float factor = (float) Mth.clamp((cloudY + half - player.getEyeY()) / (2.0 * half), 0.0, 1.0);

		level.setRainLevel(real * factor);
		lastSet = level.getRainLevel(1.0F);
	}

	/** Gives the level its real rain strength back (when the setting is turned off or the dimension has no clouds). */
	private static void restore(ClientLevel level) {
		if (lastSet >= 0.0F) {
			if (Math.abs(level.getRainLevel(1.0F) - lastSet) <= 0.002F) {
				level.setRainLevel(real);
			}

			lastSet = -1.0F;
		}
	}
}
