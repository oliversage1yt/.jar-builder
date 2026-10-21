package com.skyislands.world;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.level.chunk.LevelChunk;

/** Per-chunk cache of {@link LightIslands#scan} results, so maps do not rescan whole columns for every sample. */
public final class IslandColumns {
	public static final short NONE = Short.MIN_VALUE;

	public static final class Info {
		/** Lowest block of the island above the gap, per column (x | z << 4), or NONE. */
		public final short[] chainBottom = new short[256];
		/** Top block below the gap, per column. */
		public final short[] groundTop = new short[256];
	}

	private static final Map<Long, Info> CACHE = new LinkedHashMap<>(256, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Info> eldest) {
			return this.size() > 6000;
		}
	};

	private IslandColumns() { }

	public static synchronized Info get(LevelChunk chunk) {
		long key = chunk.getPos().pack();
		Info info = CACHE.get(key);

		if (info == null) {
			info = new Info();
			int[] out = new int[2];

			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					int i = x | (z << 4);

					if (LightIslands.scan(chunk, x, z, out)) {
						info.chainBottom[i] = (short) out[0];
						info.groundTop[i] = (short) out[1];
					} else {
						info.chainBottom[i] = NONE;
						info.groundTop[i] = NONE;
					}
				}
			}

			CACHE.put(key, info);
		}

		return info;
	}

	public static synchronized void clear() {
		CACHE.clear();
	}
}
