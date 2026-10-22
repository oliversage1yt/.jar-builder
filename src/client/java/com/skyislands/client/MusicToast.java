package com.skyislands.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import com.skyislands.SkyIslands;

/**
 * "Music toasts" for the Sky Islands songs: the small now-playing panel Minecraft shows for its own music.
 * Follows the player's vanilla "Music Toast" setting: Never = nothing, Pause Screen = shown only while the pause menu is
 * open, Pause Screen and Toast = also slides in for a few seconds when a song starts.
 *
 * <p>The box (the mod's now_playing texture) and the bobbing cloud icon are stored as pixel rectangles and drawn with plain
 * fill() calls; the text uses the normal text call. Both are looked up by reflection on first use. That keeps the build
 * independent of renames in Minecraft's drawing classes; if a call cannot be found the toasts switch themselves off with one
 * log warning.
 */
final class MusicToast {
	private static final Map<String, String> TITLES = Map.of(
			"music.swaying_islands", "Swaying Islands",
			"music.an_islands_peace", "An Island's Peace",
			"music.breathtaking_view", "Breathtaking View",
			"music.islands_sorrow", "Island's Sorrow",
			"music.singing_islands", "Singing Islands",
			"music.skyborn", "Skyborn",
			"music.battle_amongst_the_clouds", "Battle amongst the Clouds");

	private static final int NEVER = 0;
	private static final int PAUSE_ONLY = 1;
	private static final int PAUSE_AND_TOAST = 2;

	private static final long SLIDE_MS = 350;
	private static final long HOLD_MS = 5000;

	/**
	 * The toast box (your now_playing texture, 160x32, 9-slice with 5 pixel corners) as filled rectangles {x0, y0, x1, y1, argb}.
	 * Drawn on an 11 pixel wide layout: columns 0-4 are the left edge, column 5 is stretched to fit the text, columns 6-10 are
	 * the right edge.
	 */
	private static final int[][] PANEL = {
		{2, 0, 8, 1, 0xFF000000},
		{1, 1, 2, 2, 0xFF000000},
		{2, 1, 8, 3, 0xFF555555},
		{8, 1, 9, 2, 0xFF000000},
		{0, 2, 1, 29, 0xFF000000},
		{1, 2, 2, 29, 0xFF555555},
		{8, 2, 9, 31, 0xFF555555},
		{9, 2, 10, 3, 0xFF000000},
		{2, 3, 4, 4, 0xFF555555},
		{4, 3, 7, 29, 0xFF212121},
		{7, 3, 8, 4, 0xFF555555},
		{9, 3, 10, 30, 0xFF555555},
		{10, 3, 11, 30, 0xFF000000},
		{2, 4, 3, 30, 0xFF555555},
		{3, 4, 4, 28, 0xFF212121},
		{7, 4, 8, 28, 0xFF212121},
		{3, 28, 4, 31, 0xFF555555},
		{7, 28, 8, 31, 0xFF555555},
		{1, 29, 2, 30, 0xFF000000},
		{4, 29, 7, 31, 0xFF555555},
		{2, 30, 3, 31, 0xFF000000},
		{9, 30, 10, 31, 0xFF000000},
		{3, 31, 9, 32, 0xFF000000}
	};
	private static final int PANEL_H = 32;
	private static final int SLICE = 5;

	/** The cloud icon (your cloud sprite, cropped and shrunk to 32x12 keeping its own palette) as rectangles {x0, y0, x1, y1, argb}. */
	private static final int[][] CLOUD = {
		{11, 0, 12, 1, 0xFF000000},
		{12, 0, 13, 1, 0xFF717171},
		{13, 0, 15, 1, 0xFF919191},
		{10, 1, 11, 2, 0xFF717171},
		{11, 1, 12, 2, 0xFF919191},
		{12, 1, 15, 3, 0xFFFFF2F2},
		{15, 1, 18, 2, 0xFF717171},
		{9, 2, 10, 3, 0xFF000000},
		{10, 2, 12, 5, 0xFFFFF2F2},
		{15, 2, 18, 3, 0xFFFFF2F2},
		{18, 2, 19, 3, 0xFF000000},
		{9, 3, 10, 4, 0xFF919191},
		{12, 3, 13, 4, 0xFFFFF2F2},
		{13, 3, 14, 4, 0xFFC8F1FF},
		{14, 3, 15, 5, 0xFFACEAFF},
		{15, 3, 16, 4, 0xFFC8F1FF},
		{16, 3, 19, 4, 0xFFFFF2F2},
		{19, 3, 21, 4, 0xFF919191},
		{8, 4, 9, 5, 0xFF717171},
		{9, 4, 10, 5, 0xFFFFF2F2},
		{12, 4, 14, 6, 0xFFACEAFF},
		{15, 4, 17, 5, 0xFFACEAFF},
		{17, 4, 21, 5, 0xFFFFF2F2},
		{21, 4, 22, 6, 0xFF919191},
		{7, 5, 8, 6, 0xFF717171},
		{8, 5, 9, 6, 0xFF9FDEFF},
		{9, 5, 11, 7, 0xFFC8F1FF},
		{11, 5, 12, 7, 0xFFACEAFF},
		{14, 5, 15, 6, 0xFFC8F1FF},
		{15, 5, 17, 6, 0xFFFFF2F2},
		{17, 5, 18, 6, 0xFFACEAFF},
		{18, 5, 19, 7, 0xFFC8F1FF},
		{19, 5, 21, 6, 0xFFFFF2F2},
		{5, 6, 6, 7, 0xFF717171},
		{7, 6, 8, 7, 0xFF9FDEFF},
		{8, 6, 9, 7, 0xFFACEAFF},
		{12, 6, 13, 7, 0xFFC8F1FF},
		{13, 6, 14, 7, 0xFFACEAFF},
		{14, 6, 16, 7, 0xFF9FDEFF},
		{16, 6, 17, 7, 0xFFC8F1FF},
		{17, 6, 18, 7, 0xFFFFF2F2},
		{19, 6, 20, 7, 0xFFC8F1FF},
		{20, 6, 22, 8, 0xFFFFF2F2},
		{22, 6, 23, 7, 0xFF919191},
		{23, 6, 24, 7, 0xFF717171},
		{24, 6, 25, 7, 0xFF919191},
		{25, 6, 26, 9, 0xFFFFF2F2},
		{26, 6, 27, 7, 0xFF717171},
		{4, 7, 5, 8, 0xFF919191},
		{5, 7, 6, 8, 0xFFFFF2F2},
		{6, 7, 7, 8, 0xFF717171},
		{7, 7, 8, 8, 0xFFACEAFF},
		{8, 7, 11, 8, 0xFFFFF2F2},
		{11, 7, 12, 8, 0xFFC8F1FF},
		{12, 7, 14, 8, 0xFF9FDEFF},
		{14, 7, 16, 8, 0xFF91D7FB},
		{16, 7, 17, 8, 0xFF9FDEFF},
		{17, 7, 18, 8, 0xFFC8F1FF},
		{18, 7, 20, 8, 0xFFFFF2F2},
		{22, 7, 25, 8, 0xFFFFF2F2},
		{26, 7, 27, 9, 0xFFFFF2F2},
		{27, 7, 28, 8, 0xFF717171},
		{2, 8, 3, 9, 0xFF717171},
		{3, 8, 4, 9, 0xFF919191},
		{4, 8, 5, 9, 0xFFC8F1FF},
		{5, 8, 6, 9, 0xFFACEAFF},
		{6, 8, 7, 9, 0xFFC8F1FF},
		{7, 8, 9, 9, 0xFFFFF2F2},
		{9, 8, 10, 9, 0xFFACEAFF},
		{10, 8, 11, 9, 0xFF9FDEFF},
		{11, 8, 12, 9, 0xFF91D7FB},
		{12, 8, 17, 11, 0xFF81CAEF},
		{17, 8, 18, 9, 0xFF91D7FB},
		{18, 8, 19, 9, 0xFF9FDEFF},
		{19, 8, 20, 9, 0xFFACEAFF},
		{20, 8, 21, 9, 0xFFC8F1FF},
		{21, 8, 22, 9, 0xFFFFF2F2},
		{22, 8, 25, 9, 0xFFC8F1FF},
		{27, 8, 28, 10, 0xFFFFF2F2},
		{28, 8, 29, 9, 0xFF919191},
		{29, 8, 30, 9, 0xFF717171},
		{1, 9, 2, 10, 0xFF717171},
		{2, 9, 3, 10, 0xFFC8F1FF},
		{3, 9, 4, 10, 0xFFACEAFF},
		{4, 9, 5, 10, 0xFF9FDEFF},
		{5, 9, 6, 10, 0xFF91D7FB},
		{6, 9, 9, 10, 0xFF9FDEFF},
		{9, 9, 10, 10, 0xFF91D7FB},
		{10, 9, 12, 11, 0xFF81CAEF},
		{17, 9, 19, 11, 0xFF81CAEF},
		{19, 9, 21, 10, 0xFF91D7FB},
		{21, 9, 22, 10, 0xFF9FDEFF},
		{22, 9, 25, 11, 0xFF91D7FB},
		{25, 9, 26, 10, 0xFF9FDEFF},
		{26, 9, 27, 10, 0xFFC8F1FF},
		{28, 9, 29, 10, 0xFFC8F1FF},
		{29, 9, 30, 10, 0xFFFFF2F2},
		{30, 9, 31, 10, 0xFF919191},
		{0, 10, 1, 11, 0xFF717171},
		{1, 10, 2, 11, 0xFF91D7FB},
		{2, 10, 10, 11, 0xFF81CAEF},
		{19, 10, 21, 11, 0xFF81CAEF},
		{21, 10, 22, 11, 0xFF91D7FB},
		{25, 10, 29, 11, 0xFF91D7FB},
		{29, 10, 30, 11, 0xFF9FDEFF},
		{30, 10, 31, 11, 0xFFC8F1FF},
		{31, 10, 32, 11, 0xFF717171},
		{3, 11, 9, 12, 0xFF717171},
		{9, 11, 10, 12, 0xFF000000},
		{10, 11, 31, 12, 0xFF717171}
	};
	private static final int ICON_W = 32;
	private static final int ICON_H = 12;
	private static final int PAD = 8;
	private static final int GAP = 6;
	private static final int MARGIN = 0;
	/** The icon bobs up and down 2 pixels, switching every half second. */
	private static final long BOB_MS = 500;

	private static final int WHITE = 0xFFFFFFFF;

	private static Field screenField;
	private static boolean screenSearched;
	private static boolean hookFailed;

	private static String hudTitle;
	private static long hudStart;

	private static Method optionGetter;
	private static Field optionField;
	private static boolean optionSearched;
	private static boolean optionMissingLogged;

	private static Method fillMethod;
	private static Method textMethod;
	private static boolean textWantsComponent;
	private static boolean drawResolved;
	private static boolean drawFailed;

	private MusicToast() { }

	static void register() {
		HudElementRegistry.addLast(SkyIslands.id("music_toast"), (graphics, tickCounter) -> renderHud(graphics));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen) {
				hookScreenRender(screen);
			}
		});
	}

	/**
	 * Registers {@link #renderPause} to run after the pause screen has drawn. Fabric's event for this has been renamed between
	 * versions (afterRender / afterExtract ...), so it is found by name pattern at runtime and given a proxy listener.
	 */
	private static void hookScreenRender(Screen screen) {
		if (hookFailed) {
			return;
		}

		try {
			List<Method> candidates = new ArrayList<>();

			for (Method m : ScreenEvents.class.getMethods()) {
				String n = m.getName().toLowerCase();

				if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(Screen.class)
						&& n.startsWith("after") && (n.contains("render") || n.contains("extract") || n.contains("draw"))
						&& m.getGenericReturnType() instanceof ParameterizedType) {
					candidates.add(m);
				}
			}

			if (candidates.isEmpty()) {
				hookFailed = true;
				SkyIslands.LOGGER.warn("Sky Islands could not find Fabric's after-screen-render event; the pause screen toast is off.");
				return;
			}

			candidates.sort((a, b) -> Integer.compare(a.getName().length(), b.getName().length()));
			Method method = candidates.get(0);
			Object event = method.invoke(null, screen);
			Class<?> listener = (Class<?>) ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];

			Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, (self, called, args) -> {
				if (called.getDeclaringClass() == Object.class) {
					return called.getName().equals("hashCode") ? System.identityHashCode(self) : called.getName().equals("equals") ? self == args[0] : "SkyIslandsMusicToast";
				}

				if (args != null && args.length > 1) {
					renderPause(args[1]);
				}

				return null;
			});

			Event.class.getMethod("register", Object.class).invoke(event, proxy);
		} catch (Throwable t) {
			hookFailed = true;
			SkyIslands.LOGGER.warn("Sky Islands could not hook the pause screen toast: {}", t.toString());
		}
	}

	/** True while the pause menu is the open screen. The screen field is found by its type, so its name does not matter. */
	private static boolean pauseOpen(Minecraft mc) {
		try {
			if (!screenSearched) {
				screenSearched = true;

				for (Field f : Minecraft.class.getDeclaredFields()) {
					if (f.getType() == Screen.class && !Modifier.isStatic(f.getModifiers()) && (screenField == null || f.getName().equals("screen"))) {
						f.setAccessible(true);
						screenField = f;
					}
				}
			}

			return screenField != null && screenField.get(mc) instanceof PauseScreen;
		} catch (Throwable t) {
			return false;
		}
	}

	/** The display name of one of our songs, or null. */
	static String titleOf(Identifier sound) {
		return TITLES.get(sound.getPath());
	}

	/** Called by the music controller when one of our songs begins. */
	static void songStarted(Identifier sound) {
		String title = titleOf(sound);

		if (title != null) {
			hudTitle = title;
			hudStart = System.currentTimeMillis();
		}
	}

	static void reset() {
		hudTitle = null;
	}

	// ------------------------------------------------------------------ rendering

	private static void renderHud(Object graphics) {
		if (hudTitle == null || drawFailed) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();

		if (pauseOpen(mc) || setting(mc) != PAUSE_AND_TOAST) {
			return;
		}

		long t = System.currentTimeMillis() - hudStart;

		if (t > SLIDE_MS * 2 + HOLD_MS) {
			hudTitle = null;
			return;
		}

		double shown;

		if (t < SLIDE_MS) {
			shown = ease(t / (double) SLIDE_MS);
		} else if (t > SLIDE_MS + HOLD_MS) {
			shown = 1.0 - ease((t - SLIDE_MS - HOLD_MS) / (double) SLIDE_MS);
		} else {
			shown = 1.0;
		}

		int width = panelWidth(mc, hudTitle);
		int x = (int) Math.round(-width + (width + MARGIN) * shown);
		draw(graphics, mc, hudTitle, x, MARGIN);
	}

	private static void renderPause(Object graphics) {
		if (drawFailed) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();

		if (setting(mc) == NEVER) {
			return;
		}

		String title = MusicController.nowPlaying();

		if (title != null) {
			draw(graphics, mc, title, MARGIN, MARGIN);
		}
	}

	private static double ease(double v) {
		double c = Math.max(0.0, Math.min(1.0, v));
		return 1.0 - (1.0 - c) * (1.0 - c);
	}

	private static int panelWidth(Minecraft mc, String title) {
		return PAD + ICON_W + GAP + mc.font.width(title) + PAD;
	}

	private static void draw(Object g, Minecraft mc, String title, int x, int y) {
		try {
			if (!drawResolved) {
				resolveDrawing(g);
			}

			if (drawFailed) {
				return;
			}

			int w = panelWidth(mc, title);

			panel(g, x, y, w);

			boolean up = (System.currentTimeMillis() / BOB_MS) % 2 == 1;
			int iconX = x + PAD;
			int iconY = y + (PANEL_H - ICON_H) / 2 + (up ? -1 : 1);
			cloud(g, iconX, iconY);

			int textY = y + (PANEL_H - mc.font.lineHeight) / 2 + 1;
			text(g, mc.font, title, iconX + ICON_W + GAP, textY);
		} catch (Throwable t) {
			drawFailed = true;
			SkyIslands.LOGGER.warn("Sky Islands music toasts were switched off: {}", t.toString());
		}
	}

	private static void panel(Object g, int x, int y, int width) throws ReflectiveOperationException {
		for (int[] r : PANEL) {
			fill(g, x + sliceX(r[0], width), y + r[1], x + sliceX(r[2], width), y + r[3], r[4]);
		}
	}

	/** Maps a column boundary of the 11 wide layout onto the real panel width (the middle column stretches). */
	private static int sliceX(int boundary, int width) {
		return boundary <= SLICE ? boundary : width - 11 + boundary;
	}

	private static void cloud(Object g, int x, int y) throws ReflectiveOperationException {
		for (int[] r : CLOUD) {
			fill(g, x + r[0], y + r[1], x + r[2], y + r[3], r[4]);
		}
	}

	private static void fill(Object g, int x1, int y1, int x2, int y2, int color) throws ReflectiveOperationException {
		fillMethod.invoke(g, x1, y1, x2, y2, color);
	}

	private static void text(Object g, Font font, String s, int x, int y) throws ReflectiveOperationException {
		Object value = textWantsComponent ? Component.literal(s) : s;

		if (textMethod.getParameterCount() == 6) {
			textMethod.invoke(g, font, value, x, y, WHITE, true);
		} else {
			textMethod.invoke(g, font, value, x, y, WHITE);
		}
	}

	/** Finds the fill and draw-text methods on the graphics object, whatever this Minecraft version calls them. */
	private static void resolveDrawing(Object g) {
		drawResolved = true;

		for (Method m : g.getClass().getMethods()) {
			Class<?>[] p = m.getParameterTypes();
			String n = m.getName();

			if (n.equals("fill") && p.length == 5 && p[0] == int.class && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
				fillMethod = m;
			}

			boolean named = n.equals("drawString") || n.equals("text") || n.equals("drawText");

			if (named && (p.length == 5 || p.length == 6) && p[0] == Font.class && (p[1] == String.class || p[1] == Component.class)
					&& p[2] == int.class && p[3] == int.class && p[4] == int.class && (p.length == 5 || p[5] == boolean.class)) {
				if (textMethod == null || p.length > textMethod.getParameterCount()
						|| (p.length == textMethod.getParameterCount() && p[1] == String.class)) {
					textMethod = m;
					textWantsComponent = p[1] == Component.class;
				}
			}
		}

		if (fillMethod == null || textMethod == null) {
			drawFailed = true;
			SkyIslands.LOGGER.warn("Sky Islands music toasts are off: could not find the fill/text methods on {}", g.getClass().getName());
			return;
		}

		try {
			fillMethod.setAccessible(true);
			textMethod.setAccessible(true);
		} catch (RuntimeException ignored) {
			// public methods on a public class work without this
		}
	}

	// ------------------------------------------------------------------ the vanilla setting

	/** 0 = never, 1 = pause screen only, 2 = pause screen and toast. Read from the player's "Music Toast" option. */
	private static int setting(Minecraft mc) {
		try {
			if (!optionSearched) {
				findOption(mc);
			}

			Object option = null;

			if (optionGetter != null) {
				option = optionGetter.invoke(mc.options);
			} else if (optionField != null) {
				option = optionField.get(mc.options);
			}

			if (option == null) {
				return NEVER;
			}

			Object value = option.getClass().getMethod("get").invoke(option);
			String name = value instanceof Enum<?> e ? e.name() : String.valueOf(value);
			name = name.toUpperCase();

			if (name.contains("NEVER") || name.contains("OFF") || name.contains("NONE")) {
				return NEVER;
			}

			return name.contains("TOAST") ? PAUSE_AND_TOAST : PAUSE_ONLY;
		} catch (Throwable t) {
			if (!optionMissingLogged) {
				optionMissingLogged = true;
				SkyIslands.LOGGER.warn("Sky Islands could not read the Music Toast setting (toasts stay off): {}", t.toString());
			}

			return NEVER;
		}
	}

	private static void findOption(Minecraft mc) {
		optionSearched = true;
		Class<?> type = mc.options.getClass();

		for (Method m : type.getMethods()) {
			if (m.getParameterCount() == 0 && m.getName().toLowerCase().contains("musictoast")) {
				optionGetter = m;
				return;
			}
		}

		for (Field f : type.getFields()) {
			if (f.getName().toLowerCase().contains("musictoast")) {
				optionField = f;
				return;
			}
		}

		if (!optionMissingLogged) {
			optionMissingLogged = true;
			SkyIslands.LOGGER.warn("Sky Islands could not find the Music Toast option; toasts stay off.");
		}
	}
}
