package com.skyislands.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import com.skyislands.RockFilterPayload;
import com.skyislands.SkyIslands;

/**
 * The grayscale filter that is on while the player is close to a launched fallen rock (the server says when,
 * {@link RockFilterPayload}).
 *
 * <p>It is a screen effect (assets/sky_islands/post_effect/rock_grayscale.json and the shader next to it) that the game's
 * renderer applies and removes. Like the outline in {@link RockHighlight}, the renderer's methods for that are found by name:
 * if the game no longer has them the filter falls back to a flat gray veil drawn over the screen, instead of breaking the build.
 */
final class RockFilter {
	private static final Identifier EFFECT = SkyIslands.id("rock_grayscale");
	/** A flight is over long before this (the server cuts a rise off after 1200 ticks); a missed "off" message cannot leave it on. */
	private static final int MAX_TICKS = 2000;
	/** The fallback veil: gray at about two thirds opacity (ARGB). */
	private static final int VEIL = 0xAA808080;

	private static boolean resolved;
	private static Object renderer;
	private static Method setEffect;
	private static Method clearEffect;
	private static Method fillMethod;

	private static boolean on;
	private static boolean effectApplied;
	private static boolean veilFailed;
	private static int ticks;

	private RockFilter() { }

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RockFilterPayload.TYPE, (payload, context) -> set(payload.on()));
		HudElementRegistry.addLast(SkyIslands.id("rock_filter"), (graphics, tickCounter) -> drawVeil(graphics));
	}

	static void tick(Minecraft mc) {
		if (!on) {
			return;
		}

		ticks++;

		if (mc.player == null || mc.level == null || ticks > MAX_TICKS) {
			set(false);
		}
	}

	private static void set(boolean wanted) {
		if (wanted == on) {
			return;
		}

		on = wanted;
		ticks = 0;
		Minecraft mc = Minecraft.getInstance();
		resolve(mc);

		if (wanted) {
			if (setEffect != null && clearEffect != null) {
				try {
					setEffect.invoke(renderer, EFFECT);
					effectApplied = true;
					return;
				} catch (Throwable ex) {
					SkyIslands.LOGGER.warn("Sky Islands: the grayscale screen effect could not be switched on, using a gray veil instead: {}", ex.toString());
				}
			}

			effectApplied = false;
		} else if (effectApplied) {
			effectApplied = false;

			try {
				clearEffect.invoke(renderer);
			} catch (Throwable ex) {
				SkyIslands.LOGGER.warn("Sky Islands: the grayscale screen effect could not be switched off: {}", ex.toString());
			}
		}
	}

	/** The renderer's methods that switch a screen effect on and off, found by name (and listed in the log once). */
	private static void resolve(Minecraft mc) {
		if (resolved) {
			return;
		}

		resolved = true;
		List<String> found = new ArrayList<>();

		try {
			renderer = Minecraft.class.getField("gameRenderer").get(mc);

			for (Method m : renderer.getClass().getDeclaredMethods()) {
				String name = m.getName();
				String lower = name.toLowerCase(java.util.Locale.ROOT);

				if (lower.contains("posteffect") || lower.contains("effect") && lower.contains("shader") || name.equals("loadEffect") || name.equals("shutdownEffect")) {
					found.add(name + java.util.Arrays.toString(m.getParameterTypes()));
				}

				if ((name.equals("setPostEffect") || name.equals("loadEffect")) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Identifier.class) {
					m.setAccessible(true);
					setEffect = m;
				}

				if ((name.equals("clearPostEffect") || name.equals("shutdownEffect")) && m.getParameterCount() == 0) {
					m.setAccessible(true);
					clearEffect = m;
				}
			}
		} catch (Throwable ex) {
			setEffect = null;
			clearEffect = null;
		}

		SkyIslands.LOGGER.info("Sky Islands: the fallen rock grayscale filter uses {} (GameRenderer effect methods: {})",
				setEffect != null && clearEffect != null ? "the game's screen effect" : "a gray veil", found);
	}

	/** The fallback: a flat gray veil over the screen (only while the filter is on and the real screen effect is not). */
	private static void drawVeil(Object g) {
		if (!on || effectApplied || veilFailed) {
			return;
		}

		try {
			if (fillMethod == null) {
				for (Method m : g.getClass().getMethods()) {
					Class<?>[] p = m.getParameterTypes();

					if (m.getName().equals("fill") && p.length == 5 && p[0] == int.class && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
						fillMethod = m;
					}
				}

				if (fillMethod == null) {
					veilFailed = true;
					SkyIslands.LOGGER.warn("Sky Islands: the gray veil of the fallen rock filter is off: could not find the fill method on {}", g.getClass().getName());
					return;
				}
			}

			Minecraft mc = Minecraft.getInstance();
			fillMethod.invoke(g, 0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), VEIL);
		} catch (Throwable ex) {
			veilFailed = true;
			SkyIslands.LOGGER.warn("Sky Islands: the gray veil of the fallen rock filter was switched off: {}", ex.toString());
		}
	}
}
