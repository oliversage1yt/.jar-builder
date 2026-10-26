package com.skyislands.client;

import java.lang.reflect.Method;
import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.skyislands.RecipeUnlockPayload;
import com.skyislands.SkyIslands;

/**
 * The "Recipe unlocked!" popup for this mod's recipes: a 160x32 panel that slides in from the top right, shows the item(s)
 * (cycling once a second if there are several) and slides out after 5 seconds. Drawn with plain fill/text/item calls found by
 * reflection (like {@link MusicToast}), so renames in Minecraft's drawing classes switch it off with one log warning instead of
 * breaking the build. The sound is played by the server (see RecipeUnlocks).
 */
final class RecipeToastHud {
	private static final int WIDTH = 160;
	private static final int HEIGHT = 32;
	private static final long SLIDE_MS = 350;
	private static final long HOLD_MS = 5000;
	private static final long CYCLE_MS = 1000;

	private static List<String> items;
	private static long start;

	private static Method fillMethod;
	private static Method textMethod;
	private static Method itemMethod;
	private static boolean textWantsComponent;
	private static boolean resolved;
	private static boolean failed;

	private RecipeToastHud() { }

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RecipeUnlockPayload.TYPE, (payload, context) -> {
			items = payload.items();
			start = System.currentTimeMillis();
		});
		HudElementRegistry.addLast(SkyIslands.id("recipe_toast"), (graphics, tickCounter) -> render(graphics));
	}

	private static void render(Object g) {
		if (items == null || items.isEmpty() || failed) {
			return;
		}

		long t = System.currentTimeMillis() - start;

		if (t > SLIDE_MS * 2 + HOLD_MS) {
			items = null;
			return;
		}

		double shown = t < SLIDE_MS ? ease(t / (double) SLIDE_MS)
				: t > SLIDE_MS + HOLD_MS ? 1.0 - ease((t - SLIDE_MS - HOLD_MS) / (double) SLIDE_MS) : 1.0;

		try {
			Minecraft mc = Minecraft.getInstance();

			if (!resolved) {
				resolve(g);
			}

			if (failed) {
				return;
			}

			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(items.get((int) ((t / CYCLE_MS) % items.size()))));
			ItemStack stack = new ItemStack(item);
			int x = (int) Math.round(mc.getWindow().getGuiScaledWidth() - WIDTH * shown);
			int y = 0;

			fillMethod.invoke(g, x, y, x + WIDTH, y + HEIGHT, 0xFF000000);
			fillMethod.invoke(g, x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 1, 0xFF555555);
			fillMethod.invoke(g, x + 2, y + 2, x + WIDTH - 2, y + HEIGHT - 2, 0xFF212121);

			if (itemMethod != null) {
				itemMethod.invoke(g, stack, x + 8, y + 8);
			}

			text(g, mc.font, "Recipe Unlocked!", x + 30, y + 7, 0xFFFFFF55);
			text(g, mc.font, stack.getHoverName().getString(), x + 30, y + 18, 0xFFFFFFFF);
		} catch (Throwable ex) {
			failed = true;
			SkyIslands.LOGGER.warn("Sky Islands recipe popups were switched off: {}", ex.toString());
		}
	}

	private static double ease(double v) {
		double c = Math.max(0.0, Math.min(1.0, v));
		return 1.0 - (1.0 - c) * (1.0 - c);
	}

	private static void text(Object g, Font font, String s, int x, int y, int color) throws ReflectiveOperationException {
		Object value = textWantsComponent ? Component.literal(s) : s;

		if (textMethod.getParameterCount() == 6) {
			textMethod.invoke(g, font, value, x, y, color, true);
		} else {
			textMethod.invoke(g, font, value, x, y, color);
		}
	}

	/** Finds the fill, draw-text and draw-item methods on the graphics object, whatever this Minecraft version calls them. */
	private static void resolve(Object g) {
		resolved = true;

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

			if (p.length == 3 && p[0] == ItemStack.class && p[1] == int.class && p[2] == int.class && m.getReturnType() == void.class
					&& (n.equals("renderItem") || n.equals("item") || n.equals("renderFakeItem") || n.equals("fakeItem"))
					&& (itemMethod == null || n.equals("renderItem") || n.equals("item"))) {
				itemMethod = m;
			}
		}

		if (fillMethod == null || textMethod == null) {
			failed = true;
			SkyIslands.LOGGER.warn("Sky Islands recipe popups are off: could not find the fill/text methods on {}", g.getClass().getName());
		}
	}
}
