package com.skyislands.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
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
import net.minecraft.util.Mth;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

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
	/** One line of the outline: the exact shape of each block (stairs and slabs included). */
	private record Seg(double x0, double y0, double z0, double x1, double y1, double z1, Edge lattice, double cx, double cy, double cz) { }

	private static List<Seg> segs = List.of();

	private static final Identifier HUM = SkyIslands.id("rock.recall_hover");
	private static final float HUM_VOLUME = 0.7F;
	/** Played once, at full volume, when a rock is activated. */
	private static final Identifier START = SkyIslands.id("rock.recall_start");
	private static final float START_VOLUME = 1.0F;
	/** The sound fades in over this many ticks (1 second) when a launchable rock is looked at. */
	private static final int HUM_FADE_IN = 20;
	/** The sound fades out within this many ticks (1 second) when the rock is launched or the player looks away. */
	private static final int HUM_FADE_OUT = 20;

	private static FadingSound hum;
	private static boolean humFading;
	private static boolean humChecked;
	private static int humTicks;

	private static boolean sentHas;
	private static long sentPos;

	private static Method fillMethod;
	private static Method partialTickMethod;
	private static boolean resolved;
	private static boolean camResolved;
	private static boolean camOk;
	private static Object gameRenderer;
	private static Method cameraGetter;
	private static Method camPos;
	private static Method camYaw;
	private static Method camPitch;
	private static Method fovMethod;
	private static Field fovNow;
	private static Field fovOld;
	private static boolean failed;

	private RockHighlight() { }

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RockHighlightPayload.TYPE, (payload, context) -> set(payload.blocks()));
		HudElementRegistry.addLast(SkyIslands.id("rock_highlight"), (graphics, tickCounter) -> render(graphics, tickCounter));

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (hand == InteractionHand.MAIN_HAND && world instanceof ClientLevel && blocks.contains(hit.getBlockPos().asLong())) {
				// the outline and the hum go at once (the hum fades out); the launch sound plays once; the server is told to look again
				playStart();
				clear();
				sentHas = false;
				ClientPlayNetworking.send(new RockHoverPayload(false, 0L));
				return InteractionResult.SUCCESS;
			}

			return InteractionResult.PASS;
		});
	}

	/** The sound of activating a rock: plays once, from start to end, and is not tied to where the player stands. */
	private static void playStart() {
		Minecraft.getInstance().getSoundManager().play(new FadingSound(START, SoundSource.BLOCKS, START_VOLUME));
	}

	private static void set(List<Long> list) {
		blocks = list.isEmpty() ? Set.of() : new HashSet<>(list);
		segs = buildSegs(blocks);
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

		updateHum(client, !blocks.isEmpty());

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

	/** The looping sound while a launchable rock is looked at: fades in, and fades out within a second when that ends. */
	private static void updateHum(Minecraft mc, boolean want) {
		var sounds = mc.getSoundManager();

		if (hum != null) {
			humTicks++;

			if (!humChecked && humTicks == 10) {
				humChecked = true;

				if (!sounds.isActive(hum)) {
					SkyIslands.LOGGER.warn("Sky Islands: the fallen rock hover sound ({}) is not playing; check the Blocks volume slider and the resource pack", HUM);
				}
			}

			if (hum.isStopped() || (humTicks > 20 && !sounds.isActive(hum))) {
				hum = null;
				humFading = false;
			}
		}

		if (want) {
			if (hum == null) {
				hum = new FadingSound(HUM, SoundSource.BLOCKS, 0.0F);
				hum.setLoop(true);
				hum.fadeTo(HUM_VOLUME, HUM_FADE_IN, false);
				sounds.play(hum);
				humTicks = 0;
				humChecked = false;
				humFading = false;
			} else if (humFading) {
				hum.fadeTo(HUM_VOLUME, HUM_FADE_IN, false);
				humFading = false;
			}
		} else if (hum != null && !humFading) {
			hum.fadeTo(0.0F, HUM_FADE_OUT, true);
			humFading = true;
		}
	}

	// ---------------------------------------------------------------- the outline

	/** Whether the surface of the pile turns a corner at this unit edge (a flat face or the inside of the pile has no line). */
	private static boolean cornerEdge(Set<Long> set, Edge e) {
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
		return count == 1 || count == 3 || diagonal;
	}

	private static boolean nearInt(double v) {
		return Math.abs(v - Math.rint(v)) < 1.0E-4;
	}

	/** The lines of every block's real shape; the ones on the edges of the cube grid only where the pile has a corner. */
	private static List<Seg> buildSegs(Set<Long> set) {
		List<Seg> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		ClientLevel level = Minecraft.getInstance().level;

		for (long packed : set) {
			BlockPos pos = BlockPos.of(packed);
			VoxelShape shape = level == null ? net.minecraft.world.phys.shapes.Shapes.block() : level.getBlockState(pos).getShape(level, pos);

			shape.forAllEdges((x0, y0, z0, x1, y1, z1) -> {
				double ax = x0 + pos.getX();
				double ay = y0 + pos.getY();
				double az = z0 + pos.getZ();
				double bx = x1 + pos.getX();
				double by = y1 + pos.getY();
				double bz = z1 + pos.getZ();
				String key = ax <= bx && (ax != bx || (ay <= by && (ay != by || az <= bz)))
						? String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d", Math.round(ax * 64), Math.round(ay * 64), Math.round(az * 64), Math.round(bx * 64), Math.round(by * 64), Math.round(bz * 64))
						: String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d", Math.round(bx * 64), Math.round(by * 64), Math.round(bz * 64), Math.round(ax * 64), Math.round(ay * 64), Math.round(az * 64));

				if (!seen.add(key)) {
					return;
				}

				int axis = Math.abs(bx - ax) > 1.0E-6 ? 0 : (Math.abs(by - ay) > 1.0E-6 ? 1 : 2);
				boolean onGrid = axis == 0 ? nearInt(ay) && nearInt(az) : axis == 1 ? nearInt(ax) && nearInt(az) : nearInt(ax) && nearInt(ay);
				Edge lattice = null;

				if (onGrid) {
					double lo = axis == 0 ? Math.min(ax, bx) : axis == 1 ? Math.min(ay, by) : Math.min(az, bz);
					lattice = new Edge(axis, (int) (axis == 0 ? Math.floor(lo + 1.0E-4) : Math.rint(ax)), (int) (axis == 1 ? Math.floor(lo + 1.0E-4) : Math.rint(ay)),
							(int) (axis == 2 ? Math.floor(lo + 1.0E-4) : Math.rint(az)));

					if (!cornerEdge(set, lattice)) {
						return;
					}
				}

				result.add(new Seg(ax, ay, az, bx, by, bz, lattice, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
			});
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

			if (player == null || mc.level == null) {
				return;
			}

			if (!resolved) {
				resolve(g, tickCounter);
			}

			if (failed) {
				return;
			}

			if (!camResolved) {
				resolveCamera(mc);
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

			Vec3 eye;
			double yawDeg;
			double pitchDeg;
			double fovDeg;
			boolean exact = false;

			if (camOk) {
				// the game's own camera: it follows crouching smoothly, third person, and so on
				Object cam = cameraGetter.invoke(gameRenderer);
				eye = (Vec3) camPos.invoke(cam);
				yawDeg = ((Number) camYaw.invoke(cam)).doubleValue();
				pitchDeg = ((Number) camPitch.invoke(cam)).doubleValue();
				fovDeg = mc.options.fov().get();

				if (fovMethod == null && fovNow != null && fovOld != null) {
					// the game's smoothed field of view change (sprinting, flying, speed, ...) and the dip under water or in lava
					try {
						fovDeg *= Mth.lerp(partial, fovOld.getFloat(gameRenderer), fovNow.getFloat(gameRenderer));

						if (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)) {
							fovDeg *= Mth.lerp(mc.options.fovEffectScale().get(), 1.0, 0.85714287);
						}

						exact = true;
					} catch (ReflectiveOperationException | RuntimeException ex) {
						fovNow = null;
						fovOld = null;
					}
				}

				if (fovMethod != null) {
					try {
						// the field of view the game itself draws with (sprinting, speed, being under water, ...)
						fovDeg = ((Number) fovMethod.invoke(gameRenderer, cam, partial, Boolean.TRUE)).doubleValue();
						exact = true;
					} catch (ReflectiveOperationException | RuntimeException ex) {
						fovMethod = null;
					}
				}
			} else {
				if (!firstPerson(mc)) {
					return;
				}

				eye = player.getEyePosition(partial);
				yawDeg = player.getViewYRot(partial);
				pitchDeg = player.getViewXRot(partial);
				fovDeg = mc.options.fov().get();
			}

			// without the game's own field of view method, work out what the game would draw with (sprinting, speed, and the
			// narrower view in water or lava) so the outline still lines up, and stays visible, under water
			if (!exact) {
				fovDeg = estimateFov(mc, player, fovDeg);

				if (Double.isNaN(fovDeg)) {
					return;
				}
			}

			double yaw = Math.toRadians(yawDeg);
			double pitch = Math.toRadians(pitchDeg);
			double cosP = Math.cos(pitch);
			double[] f = { -Math.sin(yaw) * cosP, -Math.sin(pitch), Math.cos(yaw) * cosP };
			double[] r = { -Math.cos(yaw), 0.0, -Math.sin(yaw) };
			double[] u = { r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0] };

			int width = mc.getWindow().getGuiScaledWidth();
			int height = mc.getWindow().getGuiScaledHeight();
			double tanHalf = Math.tan(Math.toRadians(fovDeg) / 2.0);
			double aspect = width / (double) height;
			int thickness = (int) Math.max(1, Math.round(LINE_PIXELS / Math.max(1.0, mc.getWindow().getGuiScale())));

			for (Seg seg : segs) {
				if (seg.lattice() != null) {
					if (!facesCamera(seg.lattice(), eye)) {
						continue;
					}
				} else {
					// a line on a stair or slab that is not on the cube grid: only the ones on the side turned to the eye
					double mx = (seg.x0() + seg.x1()) / 2.0;
					double my = (seg.y0() + seg.y1()) / 2.0;
					double mz = (seg.z0() + seg.z1()) / 2.0;

					if ((eye.x - mx) * (mx - seg.cx()) + (eye.y - my) * (my - seg.cy()) + (eye.z - mz) * (mz - seg.cz()) < -0.02) {
						continue;
					}
				}

				double[] c0 = toCamera(new double[] { seg.x0(), seg.y0(), seg.z0() }, eye, f, r, u);
				double[] c1 = toCamera(new double[] { seg.x1(), seg.y1(), seg.z1() }, eye, f, r, u);

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

	private static Method fovModifierMethod;
	private static boolean fovModifierResolved;
	private static boolean fovEstimateLogged;

	/** The player's own field of view modifier method (the game's: sprinting, speed, flying, bow), found by name. May be null. */
	private static Method fovModifierMethod(LocalPlayer player) {
		if (!fovModifierResolved) {
			fovModifierResolved = true;

			for (Method m : player.getClass().getMethods()) {
				Class<?>[] p = m.getParameterTypes();

				if (m.getName().equals("getFieldOfViewModifier") && p.length == 2 && p[0] == boolean.class && p[1] == float.class
						&& Number.class.isAssignableFrom(boxed(m.getReturnType()))) {
					fovModifierMethod = m;
				}
			}
		}

		return fovModifierMethod;
	}

	/**
	 * The field of view the game draws with, worked out here when its own method could not be found: the option, times the
	 * speed / flying modifier, times the narrower view while the eye is in water or lava. NaN when it cannot be told.
	 */
	private static double estimateFov(Minecraft mc, LocalPlayer player, double baseFov) {
		double scale = mc.options.fovEffectScale().get();
		double fov = baseFov;

		try {
			Method method = fovModifierMethod(player);
			double modifier;

			if (method != null) {
				modifier = ((Number) method.invoke(player, firstPerson(mc), (float) scale)).doubleValue();
			} else {
				double f = 1.0;

				try {
					Object abilities = player.getClass().getMethod("getAbilities").invoke(player);

					if (abilities.getClass().getField("flying").getBoolean(abilities)) {
						f *= 1.1;
					}
				} catch (ReflectiveOperationException | RuntimeException ex) {
					// no abilities to read: not flying
				}

				f *= (player.getAttributeValue(Attributes.MOVEMENT_SPEED) / 0.1 + 1.0) / 2.0;

				if (Double.isNaN(f) || Double.isInfinite(f)) {
					f = 1.0;
				}

				modifier = Mth.lerp(scale, 1.0, f);
			}

			fov *= modifier;
		} catch (Throwable ex) {
			return Double.NaN;
		}

		if (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)) {
			fov *= Mth.lerp(scale, 1.0, 0.85714287);
		}

		if (!fovEstimateLogged) {
			fovEstimateLogged = true;
			SkyIslands.LOGGER.info("Sky Islands: the game's field of view method was not found, so the fallen rock outline works it out itself (this keeps it visible under water)");
		}

		return fov;
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

	private static Method find(Class<?> type, String... names) {
		for (String name : names) {
			for (Method m : type.getMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == 0) {
					return m;
				}
			}
		}

		return null;
	}

	/** The game's camera and the field of view it draws with, found by name (any that is missing falls back to the player). */
	private static void resolveCamera(Minecraft mc) {
		camResolved = true;

		try {
			gameRenderer = Minecraft.class.getField("gameRenderer").get(mc);
			cameraGetter = find(gameRenderer.getClass(), "getMainCamera");
			Object cam = cameraGetter == null ? null : cameraGetter.invoke(gameRenderer);

			if (cam != null) {
				camPos = find(cam.getClass(), "position", "getPosition");
				camYaw = find(cam.getClass(), "getYRot", "yRot", "getYaw");
				camPitch = find(cam.getClass(), "getXRot", "xRot", "getPitch");
				camOk = camPos != null && camYaw != null && camPitch != null && camPos.getReturnType() == Vec3.class;
			}

			for (Method m : gameRenderer.getClass().getDeclaredMethods()) {
				Class<?>[] p = m.getParameterTypes();

				if (m.getName().equals("getFov") && p.length == 3 && p[1] == float.class && p[2] == boolean.class && Number.class.isAssignableFrom(boxed(m.getReturnType()))) {
					m.setAccessible(true);
					fovMethod = m;
				}
			}

			for (Field f : gameRenderer.getClass().getDeclaredFields()) {
				String name = f.getName().toLowerCase(Locale.ROOT);

				if (f.getType() == float.class && name.contains("fov") && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					f.setAccessible(true);

					if (name.startsWith("old")) {
						fovOld = f;
					} else {
						fovNow = f;
					}
				}
			}
		} catch (Throwable ex) {
			camOk = false;
		}

		StringBuilder members = new StringBuilder();

		try {
			for (Method m : gameRenderer.getClass().getDeclaredMethods()) {
				if (m.getName().toLowerCase(Locale.ROOT).contains("fov")) {
					members.append(m.getName()).append(java.util.Arrays.toString(m.getParameterTypes())).append("->").append(m.getReturnType().getSimpleName()).append(' ');
				}
			}

			for (Field f : gameRenderer.getClass().getDeclaredFields()) {
				if (f.getName().toLowerCase(Locale.ROOT).contains("fov")) {
					members.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(' ');
				}
			}
		} catch (Throwable ex) {
			members.append("(unreadable)");
		}

		SkyIslands.LOGGER.info("Sky Islands: fallen rock outline uses {} camera and {} field of view (GameRenderer fov members: {})",
				camOk ? "the game's" : "the player's", fovMethod != null ? "the game's method for the" : fovNow != null && fovOld != null ? "the game's smoothed" : "the option's", members);
	}

	private static Class<?> boxed(Class<?> c) {
		return c == float.class ? Float.class : c == double.class ? Double.class : c == int.class ? Integer.class : c;
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
