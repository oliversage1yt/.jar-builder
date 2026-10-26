package com.skyislands.world;

/** Where the cloud deck is: its height follows slow, wide noise, so it gently rolls up and down. */
public final class CloudDeck {
	private CloudDeck() { }

	/** Height of the middle of the cloud deck above the given spot. */
	public static double heightAt(double base, double variation, double x, double z) {
		return base + noise(x, z) * variation;
	}

	/** Smooth, slowly varying value in [-1, 1] (a stand-in for the original's very low frequency simplex noise). */
	public static double noise(double x, double z) {
		return 0.5 * Math.sin(x / 9000.0 + 1.3 * Math.sin(z / 13000.0)) + 0.5 * Math.sin(z / 11000.0 + 0.7 * Math.cos(x / 15000.0));
	}
}
