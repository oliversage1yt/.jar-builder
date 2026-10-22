package com.skyislands;

/**
 * Hard-coded defaults. Most client-side values can be overridden without rebuilding by editing
 * config/sky_islands.properties (it is created on first launch). See {@link com.skyislands.client.ClientConfig}.
 */
public final class Tuning {
	private Tuning() { }

	/**
	 * Above this height, biome temperature ignores altitude (vanilla cools the air ~0.00125 per block above y=80,
	 * which is what puts snow on high islands in mild or cool biomes). Biomes that are naturally freezing at sea level
	 * (snowy plains, frozen ocean, snowy taiga, ...) still snow, so "glaciated" islands keep their snow.
	 * Side effect: natural snow caps on mountains in mild biomes disappear above this height.
	 */
	public static final int SNOW_FIX_MIN_Y = 165;

	/** The altitude temperature is clamped to when the snow fix applies (vanilla's own cut-off is 80). */
	public static final int SNOW_FIX_CLAMP_Y = 80;

	/** Islands never float lower than this, so anything below is ordinary ground. */
	public static final int ISLAND_MIN_Y = 110;
	/** A column counts as "island above ground" when an air gap at least this tall separates them. */
	public static final int ISLAND_GAP = 24;
	/** A map first used at or above this height becomes a "sky map" that shows the islands. */
	public static final int SKY_MAP_MIN_Y = 140;
}
