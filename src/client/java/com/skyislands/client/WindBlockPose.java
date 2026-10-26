package com.skyislands.client;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import com.skyislands.SkyIslands;

/**
 * The look of the Wind Spear block: a player blocking with one holds both arms straight out in front, like a zombie, with
 * the spear stretched across them (the spear's own position comes from the third person display of the
 * {@code *_windspear_block} item models).
 *
 * <p>Two small mixins feed this class (both optional, and written without naming Minecraft's render classes in code, so a
 * change in them can only switch the pose off, never break the game): one notes, when a living entity's render state is
 * filled in, whether it is blocking with a Wind Spear ({@link #record}); the other calls {@link #applyPose} at the end of
 * a humanoid model's animation (the player's body and its armor layers alike) for the states that were noted.
 */
public final class WindBlockPose {
	/** Render states of entities that block with a Wind Spear this frame (they are only compared by identity, and dropped when unused). */
	private static final Map<Object, Boolean> BLOCKING = new WeakHashMap<>();

	/** The public {@code head}, {@code rightArm} and {@code leftArm} parts of a humanoid model class. */
	private static final ClassValue<Field[]> PARTS = new ClassValue<>() {
		@Override
		protected Field[] computeValue(Class<?> type) {
			String[] names = {"head", "rightArm", "leftArm"};
			Field[] fields = new Field[names.length];

			for (int i = 0; i < names.length; i++) {
				try {
					fields[i] = type.getField(names[i]);
				} catch (ReflectiveOperationException | RuntimeException e) {
					fields[i] = null;
				}
			}

			return fields;
		}
	};

	/** How far the arms are raised (radians; 1.5708 is straight ahead), and how much of the head's up/down tilt they follow. */
	private static final float ARM_RAISE = 1.5F;
	private static final float PITCH_FOLLOW = 0.5F;

	private WindBlockPose() { }

	/** Called when a render state is filled in from its entity. */
	public static void record(Object entity, Object state) {
		if (state == null) {
			return;
		}

		if (entity instanceof LivingEntity living && isBlocking(living)) {
			BLOCKING.put(state, Boolean.TRUE);
		} else if (!BLOCKING.isEmpty()) {
			BLOCKING.remove(state);
		}
	}

	/** Called at the end of a humanoid model's animation: puts both arms out in front if the state is a blocking one. */
	public static void applyPose(Object model, Object state) {
		if (state == null || model == null || BLOCKING.isEmpty() || !BLOCKING.containsKey(state)) {
			return;
		}

		Field[] fields = PARTS.get(model.getClass());
		ModelPart head = part(fields[0], model);
		ModelPart right = part(fields[1], model);
		ModelPart left = part(fields[2], model);

		if (right == null || left == null) {
			return;
		}

		float pitch = head != null ? Math.max(-1.0F, Math.min(1.0F, head.xRot)) : 0.0F;
		float raise = -ARM_RAISE + pitch * PITCH_FOLLOW;

		// parallel and straight ahead, so the spear lies across both hands
		right.xRot = raise;
		right.yRot = 0.0F;
		right.zRot = 0.0F;
		left.xRot = raise;
		left.yRot = 0.0F;
		left.zRot = 0.0F;
	}

	private static ModelPart part(Field field, Object model) {
		if (field == null) {
			return null;
		}

		try {
			return field.get(model) instanceof ModelPart part ? part : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** True while the entity is using a Wind Spear that the server has flagged as blocking (the same flag that picks the block model). */
	static boolean isBlocking(LivingEntity entity) {
		if (!entity.isUsingItem()) {
			return false;
		}

		ItemStack stack = entity.getUseItem();

		if (stack.isEmpty()) {
			return false;
		}

		Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());

		if (!key.getNamespace().equals(SkyIslands.MOD_ID) || !key.getPath().endsWith("_windspear")) {
			return false;
		}

		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return data != null && !data.flags().isEmpty() && Boolean.TRUE.equals(data.flags().get(0));
	}
}
