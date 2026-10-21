package com.skyislands.world;

/** Which kind of map is being updated right now (set around MapItem.inventoryTick, read by the update hooks). */
public final class MapMode {
	public static final int NONE = 0;
	/** Made below the islands: islands are ignored. */
	public static final int NORMAL = 1;
	/** First used in the sky: only islands are drawn, everything else is sky blue. */
	public static final int SKY = 2;

	private static final ThreadLocal<Integer> CURRENT = ThreadLocal.withInitial(() -> NONE);

	private MapMode() { }

	public static int get() {
		return CURRENT.get();
	}

	public static void set(int mode) {
		CURRENT.set(mode);
	}
}
