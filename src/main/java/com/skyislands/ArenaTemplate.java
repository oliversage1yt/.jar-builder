package com.skyislands;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * The block layout of the boss arena ({@code skyarena_fight.nbt}), read straight from the mod's own resources.
 *
 * <p>This is the "what the arena looked like when it generated" record that {@link ArenaReset} restores. It only knows
 * about plain strings and numbers (no Minecraft classes), so the file format is parsed here with a tiny NBT reader.
 * Every position is stored relative to the altar (the Crying Obsidian in the middle of the floor), so the layout can be
 * stamped around any altar in any of the four rotations.
 */
final class ArenaTemplate {
	static final String RESOURCE = "/data/sky_islands/structure/skyarena_fight.nbt";

	/** Block state strings such as {@code minecraft:tuff_brick_stairs[facing=north,half=bottom]}; air is stored too. */
	final String[] palette;
	/** One entry per template cell: position relative to the altar, and the palette index of the block there. */
	final int[] dx;
	final int[] dy;
	final int[] dz;
	final int[] state;

	private ArenaTemplate(String[] palette, int[] dx, int[] dy, int[] dz, int[] state) {
		this.palette = palette;
		this.dx = dx;
		this.dy = dy;
		this.dz = dz;
		this.state = state;
	}

	int size() {
		return dx.length;
	}

	/** Loads the template from the mod's resources. */
	static ArenaTemplate load() throws IOException {
		try (InputStream raw = ArenaTemplate.class.getResourceAsStream(RESOURCE)) {
			if (raw == null) {
				throw new IOException("missing resource " + RESOURCE);
			}

			return parse(raw);
		}
	}

	@SuppressWarnings("unchecked")
	static ArenaTemplate parse(InputStream raw) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
			if (in.readByte() != 10) {
				throw new IOException("not a compound tag");
			}

			in.readUTF(); // root name
			Map<String, Object> root = (Map<String, Object>) readPayload(in, (byte) 10);

			List<Object> paletteTag = (List<Object>) root.get("palette");
			List<Object> blocksTag = (List<Object>) root.get("blocks");

			if (paletteTag == null || blocksTag == null) {
				throw new IOException("template has no palette or blocks");
			}

			String[] palette = new String[paletteTag.size()];

			for (int i = 0; i < palette.length; i++) {
				Map<String, Object> entry = (Map<String, Object>) paletteTag.get(i);
				String name = (String) entry.get("Name");
				Map<String, Object> props = (Map<String, Object>) entry.get("Properties");

				if (name.equals("minecraft:chest")) {
					// The chest is swapped for the boss marker at worldgen, and the marker is removed when the boss wakes.
					name = "minecraft:air";
					props = null;
				}

				palette[i] = props == null || props.isEmpty() ? name : name + "[" + joinProperties(props) + "]";
			}

			int n = blocksTag.size();
			int[] x = new int[n];
			int[] y = new int[n];
			int[] z = new int[n];
			int[] st = new int[n];
			int altar = -1;

			for (int i = 0; i < n; i++) {
				Map<String, Object> block = (Map<String, Object>) blocksTag.get(i);
				List<Object> pos = (List<Object>) block.get("pos");
				x[i] = (Integer) pos.get(0);
				y[i] = (Integer) pos.get(1);
				z[i] = (Integer) pos.get(2);
				st[i] = (Integer) block.get("state");

				if (palette[st[i]].equals("sky_islands:glowing_obsidian")) {
					altar = i;
				}
			}

			if (altar < 0) {
				throw new IOException("template has no glowing obsidian altar");
			}

			int ax = x[altar];
			int ay = y[altar];
			int az = z[altar];

			for (int i = 0; i < n; i++) {
				x[i] -= ax;
				y[i] -= ay;
				z[i] -= az;
			}

			return new ArenaTemplate(palette, x, y, z, st);
		}
	}

	private static String joinProperties(Map<String, Object> props) {
		StringBuilder sb = new StringBuilder();

		for (Map.Entry<String, Object> e : new TreeMap<>(props).entrySet()) {
			if (sb.length() > 0) {
				sb.append(',');
			}

			sb.append(e.getKey()).append('=').append(e.getValue());
		}

		return sb.toString();
	}

	// ------------------------------------------------------------------ minimal NBT reader

	private static Object readPayload(DataInputStream in, byte type) throws IOException {
		switch (type) {
			case 1:
				return in.readByte();
			case 2:
				return in.readShort();
			case 3:
				return in.readInt();
			case 4:
				return in.readLong();
			case 5:
				return in.readFloat();
			case 6:
				return in.readDouble();
			case 7: {
				byte[] a = new byte[in.readInt()];
				in.readFully(a);
				return a;
			}
			case 8:
				return in.readUTF();
			case 9: {
				byte elementType = in.readByte();
				int length = in.readInt();
				List<Object> list = new ArrayList<>(Math.max(0, length));

				for (int i = 0; i < length; i++) {
					list.add(readPayload(in, elementType));
				}

				return list;
			}
			case 10: {
				Map<String, Object> map = new TreeMap<>();

				while (true) {
					byte t = in.readByte();

					if (t == 0) {
						return map;
					}

					String name = in.readUTF();
					map.put(name, readPayload(in, t));
				}
			}
			case 11: {
				int[] a = new int[in.readInt()];

				for (int i = 0; i < a.length; i++) {
					a[i] = in.readInt();
				}

				return a;
			}
			case 12: {
				long[] a = new long[in.readInt()];

				for (int i = 0; i < a.length; i++) {
					a[i] = in.readLong();
				}

				return a;
			}
			default:
				throw new IOException("unknown tag type " + type);
		}
	}
}
