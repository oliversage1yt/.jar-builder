package com.skyislands;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The golden copies of a launched fallen rock (the glow around it, and the ghost copies it leaves behind as it rises): glowing
 * block display entities. This class only writes the commands; {@link FallenRocks} decides when to run them.
 */
final class RockFx {
	/** Every effect entity carries this tag, so none can be left behind in the world. */
	static final String TAG = "sky_islands_fx";
	/** The colour of the glow: a warm gold. */
	private static final int GOLD = 0xFFD24A;
	private static final Pattern STATE = Pattern.compile("^Block\\{([^}]*)\\}(?:\\[(.*)\\])?$");
	private static final String IDENTITY = "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]";

	private RockFx() { }

	/**
	 * The block_state data of a block state, for a display entity. This version of the game saves block states as
	 * {id, properties}; the older {Name, Properties} are given as well, and whichever the game reads is used.
	 */
	static String blockState(BlockState state) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		StringBuilder props = new StringBuilder();
		Matcher m = STATE.matcher(state.toString());

		if (m.matches() && m.group(2) != null) {
			for (String part : m.group(2).split(",")) {
				String[] kv = part.trim().split("=");

				if (kv.length == 2) {
					if (props.length() > 0) {
						props.append(',');
					}

					props.append(kv[0]).append(":\"").append(kv[1]).append('"');
				}
			}
		}

		String properties = props.length() > 0 ? ",properties:{" + props + "},Properties:{" + props + "}" : "";
		return "{id:\"" + id + "\",Name:\"" + id + "\"" + properties + "}";
	}

	/** Summons one glowing golden copy of a block. {@code enlarged}: a little bigger than the block, so it does not flicker over it. */
	static String summon(double x, double y, double z, String blockState, List<String> tags, boolean enlarged) {
		StringBuilder list = new StringBuilder();

		for (String tag : tags) {
			if (list.length() > 0) {
				list.append(',');
			}

			list.append('"').append(tag).append('"');
		}

		String transformation = enlarged
				? ",transformation:{" + IDENTITY + ",scale:[1.04f,1.04f,1.04f],translation:[-0.02f,-0.02f,-0.02f]}"
				: "";
		return String.format(Locale.ROOT,
				"summon minecraft:block_display %.3f %.3f %.3f {block_state:%s,Tags:[%s],Glowing:1b,glow_color_override:%d,brightness:{sky:15,block:15},teleport_duration:1%s}",
				x, y, z, blockState, list, GOLD, transformation);
	}

	static String teleport(String tag, double x, double y, double z) {
		return String.format(Locale.ROOT, "tp @e[type=minecraft:block_display,tag=%s,limit=1] %.3f %.3f %.3f", tag, x, y, z);
	}

	/** The copies with this tag shrink to nothing, from the middle of each block, over 12 ticks. */
	static String shrink(String tag) {
		return "execute as @e[type=minecraft:block_display,tag=" + tag + "] run data merge entity @s {start_interpolation:0,interpolation_duration:12,"
				+ "transformation:{" + IDENTITY + ",scale:[0f,0f,0f],translation:[0.5f,0.5f,0.5f]}}";
	}

	static String kill(String tag) {
		return "kill @e[type=minecraft:block_display,tag=" + tag + "]";
	}
}
