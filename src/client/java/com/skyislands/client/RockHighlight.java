package com.skyislands.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.skyislands.RockHighlightPayload;
import com.skyislands.RockHoverPayload;
import com.skyislands.SkyIslands;

/**
 * The golden outline on a fallen rock the player looks at.
 *
 * <p>Every tick the client tells the server which block is under the crosshair (only when it is a block a rock is made of;
 * the crosshair target is already limited to normal block reach). When that block belongs to a rock that is ready, the server
 * answers with the rock's blocks and the outline is drawn until the player looks away or the server says the rock is no longer
 * ready (it was launched, it is cooling down, it was broken, something covers it). Right clicking while the outline is up is
 * used up by the mod (UseBlockCallback), so a block in hand is not placed against the rock; the server does the launch.
 *
 * <p>The outline is drawn as 2D lines in the HUD layer from the camera position and view direction (like {@link RecipeToastHud}
 * it uses the fill call found by reflection, so a rename in Minecraft's drawing classes switches the outline off with one log
 * warning instead of breaking the build). Only the edges of the whole pile that face the camera are drawn.
 */
final class RockHighlight {
	private static final int GOLD = 0xFFFFD700;
	/** Edge thickness in real screen pixels. */
	private static final double LINE_PIXELS = 2.0;
	private static final double NEAR = 0.05;

	private record Edge(int axis, int x, int y, int z) { }

	private static Set<Long> blocks = Set.of();
	private static List<Edge> edges = List.of();

	private static boolean sentHas;
	private static long sentPos;

	private static Method fillMethod;
	private static Method partialTickMethod;
	private static boolean resolved;
	private static boolean failed;

	private RockHighlight() { }

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RockHighlightPayload.TYPE, (payload, context) -> set(payload.blocks()));
		HudElementRegistry.addLast(SkyIslands.id("rock_highlight"), (graphics, tickCounter) -> render(graphics, tickCounter));

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (hand == InteractionHand.MAIN_HAND && world instanceof ClientLevel && blocks.contains(hit.getBlockPos().asLong())) {
				return InteractionResult.SUCCESS;
			}

			return InteractionResult.PASS;
		});
	}

	private static void set(List<Long> list) {
		blocks = list.isEmpty() ? Set.of() : new HashSet<>(list);
		edges = buildEdges(blocks);
	}

	private static void clear() {
		if (!blocks.isEmpty()) {
			set(List.of());
		}
	}

	private static boolean isRockBlock(BlockState state) {
		return state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.CHISELED_TUFF)
				|| state.is(Blocks.COBBLED_DEEPSLATE_STAIRS) || state.is(Blocks.COBBLED_DEEPSLATE_SLAB);
	}

	// ---------------------------------------------------------------- telling the server what is looked at

	static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		boolean has = false;
		long pos = 0L;

		if (player != null && client.level != null && client.gui.screen() == null && client.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK && isRockBlock(client.level.getBlockState(hit.getBlockPos()))) {
			has = true;
			pos = hit.getBlockPos().asLong();
		}

		if (!has || !blocks.contains(pos)) {
			// not looking at the outlined rock any more: the outline goes at once (the server is told below)
			clear();
		}

		if (player == null) {
			sentHas = false;
			return;
		}

		if (has != sentHas || (has && pos != sentPos)) {
			sentHas = has;
			sentPos = pos;
			ClientPlayNetworking.send(new RockHoverPayload(has, pos));
		}
	}

	// ---------------------------------------------------------------- the outline

	/** The edges that outline the whole pile: not the ones inside a flat face, only those where the surface turns a corner. */
	private static List<Edge> buildEdges(Set<Long> set) {
		Set<Edge> candidates = new HashSet<>();

		for (long packed : set) {
			BlockPos b = BlockPos.of(packed);

			for (int u = 0; u <= 1; u++) {
				for (int v = 0; v <= 1; v++) {
					candidates.add(new Edge(0, b.getX(), b.getY() + u, b.getZ() + v));
					candidates.add(new Edge(1, b.getX() + u, b.getY(), b.getZ() + v));
					candidates.add(new Edge(2, b.getX() + u, b.getY() + v, b.getZ()));
				}
			}
		}

		List<Edge> result = new ArrayList<>();

		for (Edge e : candidates) {
			int count = 0;
			boolean[][] occupied = new boolean[2][2];

			for (int i = 0; i < 2; i++) {
				for (int j = 0; j < 2; j++) {
					occupied[i][j] = set.contains(cell(e, i - 1, j - 1));

					if (occupied[i][j]) {
						count++;
					}
				}
			}

			boolean diagonal = (occupied[0][0] && occupied[1][1] && !occupied[0][1] && !occupied[1][0])
					|| (occupied[0][1] && occupied[1][0] && !occupied[0][0] && !occupied[1][1]);

			if (count == 1 || count == 3 || diagonal) {
				result.add(e);
			}
		}

		return result;
	}

	/** One of the four blocks around an edge; (du, dv) are -1 or 0 along the two axes the edge does not run along. */
	private static long cell(Edge e, int du, int dv) {
		return switch (e.axis()) {
			case 0 -> BlockPos.asLong(e.x(), e.y() + du, e.z() + dv);
			case 1 -> BlockPos.asLong(e.x() + du, e.y(), e.z() + dv);
			default -> BlockPos.asLong(e.x() + du, e.y() + dv, e.z());
		};
	}

	/** True when at least one face that this edge belongs to is part of the pile's surface and turned towards the eye. */
	private static boolean facesCamera(Edge e, Vec3 eye) {
		int[] lattice = { e.x(), e.y(), e.z() };
		int a = e.axis();

		for (int n = 0; n < 3; n++) {
			if (n == a) {
				continue;
			}

			int m = 3 - a - n;

			for (int side = -1; side <= 0; side++) {
				int[] low = lattice.clone();
				int[] high = lattice.clone();
				low[n] -= 1;
				low[m] += side;
				high[m] += side;
				boolean lowOccupied = blocks.contains(BlockPos.asLong(low[0], low[1], low[2]));
				boolean highOccupied = blocks.contains(BlockPos.asLong(high[0], high[1], high[2]));

				if (lowOccupied == highOccupied) {
					continue;
				}

				// the face lies in the plane lattice[n]; it points from the occupied block towards the empty one
				double[] center = { lattice[0], lattice[1], lattice[2] };
				center[a] += 0.5;
				center[m] += side + 0.5;
				double[] eyePos = { eye.x, eye.y, eye.z };
				double toEye = eyePos[n] - center[n];

				if (lowOccupied ? toEye < 0 : toEye > 0) {
					continue;
				}

				return true;
			}
		}

		return false;
	}

	private static void render(Object g, Object tickCounter) {
		if (blocks.isEmpty() || failed) {
			return;
		}

		try {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;

			// fast movement widens the field of view, which this drawing does not follow: no outline then
			if (player == null || mc.level == null || player.isSprinting() || player.isFallFlying() || !firstPerson(mc)) {
				return;
			}

			if (!resolved) {
				resolve(g, tickCounter);
			}

			if (failed) {
				return;
			}

			float partial = 1.0F;

			if (partialTickMethod != null) {
				try {
					partial = ((Number) partialTickMethod.invoke(tickCounter, Boolean.FALSE)).floatValue();
				} catch (ReflectiveOperationException | RuntimeException ex) {
					// the tick counter class is not reachable: draw with the end-of-tick position instead
					partialTickMethod = null;
				}
			}

			Vec3 eye = player.getEyePosition(partial);
			double yaw = Math.toRadians(player.getViewYRot(partial));
			double pitch = Math.toRadians(player.getViewXRot(partial));
			double cosP = Math.cos(pitch);
			double[] f = { -Math.sin(yaw) * cosP, -Math.sin(pitch), Math.cos(yaw) * cosP };
			double[] r = { -Math.cos(yaw), 0.0, -Math.sin(yaw) };
			double[] u = { r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0] };

			int width = mc.getWindow().getGuiScaledWidth();
			int height = mc.getWindow().getGuiScaledHeight();
			double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
			double aspect = width / (double) height;
			int thickness = (int) Math.max(1, Math.round(LINE_PIXELS / Math.max(1.0, mc.getWindow().getGuiScale())));

			for (Edge e : edges) {
				if (!facesCamera(e, eye)) {
					continue;
				}

				double[] p0 = { e.x(), e.y(), e.z() };
				double[] p1 = { e.x(), e.y(), e.z() };
				p1[e.axis()] += 1.0;
				double[] c0 = toCamera(p0, eye, f, r, u);
				double[] c1 = toCamera(p1, eye, f, r, u);

				// cut the part behind the camera off
				if (c0[2] < NEAR && c1[2] < NEAR) {
					continue;
				}

				if (c0[2] < NEAR) {
					c0 = lerp(c0, c1, (NEAR - c0[2]) / (c1[2] - c0[2]));
				} else if (c1[2] < NEAR) {
					c1 = lerp(c1, c0, (NEAR - c1[2]) / (c0[2] - c1[2]));
				}

				double x0 = width / 2.0 * (1.0 + c0[0] / (c0[2] * tanHalf * aspect));
				double y0 = height / 2.0 * (1.0 - c0[1] / (c0[2] * tanHalf));
				double x1 = width / 2.0 * (1.0 + c1[0] / (c1[2] * tanHalf * aspect));
				double y1 = height / 2.0 * (1.0 - c1[1] / (c1[2] * tanHalf));
				line(g, x0, y0, x1, y1, width, height, thickness);
			}
		} catch (Throwable ex) {
			failed = true;
			SkyIslands.LOGGER.warn("Sky Islands: the golden outline on fallen rocks was switched off: {}", ex.toString());
		}
	}

	private static double[] toCamera(double[] p, Vec3 eye, double[] f, double[] r, double[] u) {
		double dx = p[0] - eye.x;
		double dy = p[1] - eye.y;
		double dz = p[2] - eye.z;
		return new double[] { dx * r[0] + dy * r[1] + dz * r[2], dx * u[0] + dy * u[1] + dz * u[2], dx * f[0] + dy * f[1] + dz * f[2] };
	}

	private static double[] lerp(double[] a, double[] b, double t) {
		return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
	}

	/** A line made of filled rectangles: one per run of pixels that share a row (or a column, for steep lines). */
	private static void line(Object g, double x0, double y0, double x1, double y1, int width, int height, int t) throws ReflectiveOperationException {
		// keep it near the screen so a line that starts far away does not take thousands of steps
		double[] clipped = clip(x0, y0, x1, y1, -4.0, -4.0, width + 4.0, height + 4.0);

		if (clipped == null) {
			return;
		}

		x0 = clipped[0];
		y0 = clipped[1];
		x1 = clipped[2];
		y1 = clipped[3];
		double dx = x1 - x0;
		double dy = y1 - y0;
		int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));

		if (steps <= 0) {
			run(g, true, (int) Math.round(x0), (int) Math.round(x0), (int) Math.round(y0), t);
			return;
		}

		boolean wide = Math.abs(dx) >= Math.abs(dy);
		int runStart = (int) Math.round(wide ? x0 : y0);
		int prevAlong = runStart;
		int runAcross = (int) Math.round(wide ? y0 : x0);

		for (int i = 1; i <= steps; i++) {
			double s = i / (double) steps;
			int along = (int) Math.round(wide ? x0 + dx * s : y0 + dy * s);
			int across = (int) Math.round(wide ? y0 + dy * s : x0 + dx * s);

			if (across != runAcross) {
				run(g, wide, runStart, prevAlong, runAcross, t);
				runStart = along;
				runAcross = across;
			}

			prevAlong = along;
		}

		run(g, wide, runStart, prevAlong, runAcross, t);
	}

	private static void run(Object g, boolean wide, int from, int to, int across, int t) throws ReflectiveOperationException {
		int lo = Math.min(from, to);
		int hi = Math.max(from, to);
		int half = t / 2;

		if (wide) {
			fillMethod.invoke(g, lo - half, across - half, hi + t - half, across + t - half, GOLD);
		} else {
			fillMethod.invoke(g, across - half, lo - half, across + t - half, hi + t - half, GOLD);
		}
	}

	/** Liang-Barsky: the part of the segment inside the rectangle, or null when none of it is. */
	private static double[] clip(double x0, double y0, double x1, double y1, double minX, double minY, double maxX, double maxY) {
		double t0 = 0.0;
		double t1 = 1.0;
		double dx = x1 - x0;
		double dy = y1 - y0;
		double[] p = { -dx, dx, -dy, dy };
		double[] q = { x0 - minX, maxX - x0, y0 - minY, maxY - y0 };

		for (int i = 0; i < 4; i++) {
			if (p[i] == 0.0) {
				if (q[i] < 0.0) {
					return null;
				}
			} else {
				double t = q[i] / p[i];

				if (p[i] < 0.0) {
					if (t > t1) {
						return null;
					}

					t0 = Math.max(t0, t);
				} else {
					if (t < t0) {
						return null;
					}

					t1 = Math.min(t1, t);
				}
			}
		}

		return new double[] { x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy };
	}

	/** First person only: the outline is drawn from the player's eye. Anything unexpected counts as first person. */
	private static boolean firstPerson(Minecraft mc) {
		try {
			Object type = mc.options.getClass().getMethod("getCameraType").invoke(mc.options);
			return (Boolean) type.getClass().getMethod("isFirstPerson").invoke(type);
		} catch (Throwable ex) {
			return true;
		}
	}

	private static void resolve(Object g, Object tickCounter) {
		resolved = true;

		for (Method m : g.getClass().getMethods()) {
			Class<?>[] p = m.getParameterTypes();

			if (m.getName().equals("fill") && p.length == 5 && p[0] == int.class && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
				fillMethod = m;
			}
		}

		for (Method m : tickCounter.getClass().getMethods()) {
			if (m.getName().equals("getGameTimeDeltaPartialTick") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
				partialTickMethod = m;
			}
		}

		if (fillMethod == null) {
			failed = true;
			SkyIslands.LOGGER.warn("Sky Islands: the golden outline on fallen rocks is off: could not find the fill method on {}", g.getClass().getName());
		}
	}
}
