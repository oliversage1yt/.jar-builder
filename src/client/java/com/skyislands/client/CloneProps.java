package com.skyislands.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.skyislands.BossBackProps;
import com.skyislands.BossBackProps.Kind;
import com.skyislands.SkyIslands;

/**
 * The weapons on the Sky Illusioner's body, drawn on the images of the boss that the game shows while it is invisible (its
 * split attack). Those images are drawn by the client alone, as extra copies of the boss's model, so the server cannot put
 * anything on them: this draws the same weapons, in the same places and under the same rules as {@link BossBackProps},
 * every time the game draws one of the images. (While the boss is visible the server's own pictures are used, and while it
 * is invisible those are hidden, so a weapon is never drawn twice.)
 *
 * <p>Like {@link WindBlockPose} this is fed by two optional mixins and written so that a change in Minecraft's render classes
 * can only make the weapons not appear on the images, never break the game: the item drawing is looked up by reflection.
 */
public final class CloneProps {
	/** Render states of invisible bosses: which weapons are to be drawn (indexed by Kind). */
	private static final Map<Object, boolean[]> SHOWN = new WeakHashMap<>();

	private static final Map<Kind, Object> RENDER_STATES = new EnumMap<>(Kind.class);
	private static Method updateMethod;
	private static Method submitMethod;
	private static Method resolverGetter;
	private static Class<?> renderStateClass;
	private static boolean failed;
	private static boolean warned;
	private static Field bodyRotField;
	private static Field lightField;
	private static Class<?> fieldsOf;

	private static final int FULL_BRIGHT = 15728880;
	private static final int NO_OVERLAY = 655360;

	private CloneProps() { }

	/** Called when a render state is filled in from its entity. */
	public static void record(Object entity, Object state) {
		if (state == null) {
			return;
		}

		if (entity instanceof Mob mob && mob.isInvisible() && mob.hasCustomName() && mob.getName().getString().equals(BossBackProps.BOSS_NAME)) {
			boolean drawn = mob.isAggressive();
			ItemStack hand = mob.getMainHandItem();
			boolean[] shown = new boolean[Kind.values().length];

			for (Kind kind : Kind.values()) {
				shown[kind.ordinal()] = !BossBackProps.inHand(kind, hand, drawn);
			}

			SHOWN.put(state, shown);
		} else if (!SHOWN.isEmpty()) {
			SHOWN.remove(state);
		}
	}

	/** Called at the end of drawing the boss model (once for each image of it): the pose stack is at the boss's feet. */
	public static void render(Object state, PoseStack poseStack, Object collector) {
		if (failed || state == null || SHOWN.isEmpty()) {
			return;
		}

		boolean[] shown = SHOWN.get(state);

		if (shown == null) {
			return;
		}

		try {
			if (!lookUp(state)) {
				return;
			}

			float bodyRot = bodyRotField.getFloat(state);
			int light = lightField != null ? lightField.getInt(state) : FULL_BRIGHT;

			for (Kind kind : Kind.values()) {
				if (shown[kind.ordinal()]) {
					draw(kind, poseStack, collector, bodyRot, light);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			failed = true;
			SkyIslands.LOGGER.warn("Sky Islands: could not draw the weapons on the boss's images; they are not shown on them", e);
		}
	}

	private static void draw(Kind kind, PoseStack poseStack, Object collector, float bodyRot, int light) throws ReflectiveOperationException {
		Object renderState = RENDER_STATES.get(kind);

		if (renderState == null) {
			renderState = renderStateClass.getConstructor().newInstance();
			RENDER_STATES.put(kind, renderState);
		}

		Minecraft client = Minecraft.getInstance();
		Object resolver = resolverGetter.invoke(client);
		ItemStack stack = stackOf(kind);
		Class<?>[] types = updateMethod.getParameterTypes();
		Object[] args = new Object[types.length];

		for (int i = 0; i < types.length; i++) {
			if (types[i] == renderStateClass) {
				args[i] = renderState;
			} else if (types[i] == ItemStack.class) {
				args[i] = stack;
			} else if (types[i] == ItemDisplayContext.class) {
				args[i] = ItemDisplayContext.NONE;
			} else if (types[i] == Level.class) {
				args[i] = client.level;
			} else if (types[i] == int.class) {
				args[i] = 0;
			}
		}

		updateMethod.invoke(resolver, args);

		// the same chain as the server's pictures: the body's turn, the place on the body, the picture's own turns, its size, and
		// the half turn that item displays give their item
		poseStack.pushPose();

		try {
			rotate(poseStack, new Quaternionf().rotationY((float) -Math.toRadians(bodyRot)));
			poseStack.translate(kind.side, kind.height, kind.back);
			rotate(poseStack, new Quaternionf().rotationY((float) Math.toRadians(kind.faceYaw)));
			rotate(poseStack, new Quaternionf().rotationZ((float) Math.toRadians(kind.roll)));
			poseStack.scale((float) kind.scale, (float) kind.scale, (float) kind.scale);
			rotate(poseStack, new Quaternionf().rotationY((float) Math.PI));

			Class<?>[] submitTypes = submitMethod.getParameterTypes();
			Object[] submitArgs = new Object[submitTypes.length];
			int ints = 0;

			for (int i = 0; i < submitTypes.length; i++) {
				if (submitTypes[i] == PoseStack.class) {
					submitArgs[i] = poseStack;
				} else if (submitTypes[i] == int.class) {
					submitArgs[i] = ints == 0 ? light : ints == 1 ? NO_OVERLAY : 0;
					ints++;
				} else if (submitTypes[i].isInstance(collector)) {
					submitArgs[i] = collector;
				}
			}

			submitMethod.invoke(renderState, submitArgs);
		} finally {
			poseStack.popPose();
		}
	}

	/**
	 * Turns the pose stack by a rotation. The method's parameter changed between versions (a quaternion, a matrix), so whichever
	 * form this version has is looked up by name and used.
	 */
	private static void rotate(PoseStack poseStack, Quaternionf rotation) throws ReflectiveOperationException {
		for (Method m : PoseStack.class.getMethods()) {
			if (m.getName().equals("mulPose") && m.getParameterCount() == 1) {
				Class<?> type = m.getParameterTypes()[0];

				if (type.isAssignableFrom(Quaternionf.class)) {
					m.invoke(poseStack, rotation);
					return;
				}

				if (type.isAssignableFrom(Matrix4f.class)) {
					m.invoke(poseStack, new Matrix4f().rotation(rotation));
					return;
				}
			}
		}

		for (Method m : PoseStack.class.getMethods()) {
			if (m.getName().equals("rotateAround") && m.getParameterCount() == 4 && m.getParameterTypes()[0].isAssignableFrom(Quaternionf.class)) {
				m.invoke(poseStack, rotation, 0.0F, 0.0F, 0.0F);
				return;
			}
		}

		throw new NoSuchMethodException("PoseStack has no rotation method this mod knows");
	}

	private static ItemStack stackOf(Kind kind) {
		return switch (kind) {
			case BOW -> new ItemStack(Items.BOW);
			case AXE -> new ItemStack(Items.DIAMOND_AXE);
			case SPEAR -> {
				ItemStack stick = new ItemStack(Items.STICK);
				stick.set(DataComponents.ITEM_MODEL, SkyIslands.id("diamond_windspear_projectile"));
				yield stick;
			}
		};
	}

	/** Finds the game's item drawing methods and the state's fields, once. Returns false if they are not there. */
	private static boolean lookUp(Object state) throws ReflectiveOperationException {
		if (updateMethod != null && fieldsOf == state.getClass()) {
			return true;
		}

		if (updateMethod == null) {
			renderStateClass = Class.forName("net.minecraft.client.renderer.item.ItemStackRenderState");
			resolverGetter = Minecraft.class.getMethod("getItemModelResolver");
			Class<?> resolverClass = resolverGetter.getReturnType();

			for (Method m : resolverClass.getMethods()) {
				if (m.getName().equals("updateForTopItem") && m.getParameterCount() == 6) {
					updateMethod = m;
				}
			}

			for (Method m : renderStateClass.getMethods()) {
				if (m.getName().equals("submit") && m.getParameterCount() == 5) {
					submitMethod = m;
				}
			}

			if (updateMethod == null || submitMethod == null) {
				failed = true;
				SkyIslands.LOGGER.warn("Sky Islands: the item drawing methods were not found; the weapons are not shown on the boss's images");
				updateMethod = null;
				return false;
			}
		}

		bodyRotField = state.getClass().getField("bodyRot");

		try {
			lightField = state.getClass().getField("lightCoords");
		} catch (ReflectiveOperationException e) {
			lightField = null;
		}

		fieldsOf = state.getClass();
		return true;
	}
}
