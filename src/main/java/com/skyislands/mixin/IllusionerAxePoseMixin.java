package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/**
 * Makes the Sky Illusioner hold its diamond axe (or the diamond spear it throws in the last phase) like a Vindicator (arms
 * raised, weapon visible) instead of the bow-aiming pose. The spear it lunges with in the axe phase (marked "lunge", see
 * ArenaBoss) is held out in front of it instead, the way a spear is held for a charge. The target is named by string, with both package names Minecraft has used for the Illusioner, so that a version
 * where the class or method is missing simply leaves the mixin unused instead of breaking the build or the game.
 */
@Mixin(targets = {
		"net.minecraft.world.entity.monster.illager.Illusioner",
		"net.minecraft.world.entity.monster.Illusioner"
})
public abstract class IllusionerAxePoseMixin {
	@Inject(method = "getArmPose", at = @At("RETURN"), cancellable = true, require = 0)
	private void skyIslands$axePose(CallbackInfoReturnable<Enum<?>> cir) {
		Enum<?> original = cir.getReturnValue();

		if (original == null || original.name().equals("SPELLCASTING")) {
			return;
		}

		Mob self = (Mob) (Object) this;

		if (!self.isAggressive()) {
			return;
		}

		Object[] poses = original.getDeclaringClass().getEnumConstants();

		if (holdsLungeSpear(self)) {
			// Held out in front: both arms forward (the crossbow hold; the lunge spear's third person model is turned to point along
			// them), otherwise a spear pose if this version has one, otherwise raised.
			for (String name : new String[] {"CROSSBOW_HOLD", "SPEAR", "ATTACKING"}) {
				for (Object pose : poses) {
					if (pose instanceof Enum<?> e && e.name().equals(name)) {
						cir.setReturnValue(e);
						return;
					}
				}
			}

			return;
		}

		if (!holdsRaisedWeapon(self)) {
			return;
		}

		for (Object pose : poses) {
			if (pose instanceof Enum<?> e && e.name().equals("ATTACKING")) {
				cir.setReturnValue(e);
				return;
			}
		}
	}

	/** The boss's lunge spear carries a "lunge" string in its custom model data (it is a synced component, so clients can see it). */
	private static boolean holdsLungeSpear(Mob self) {
		ItemStack stack = self.getMainHandItem();

		if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("spear")) {
			return false;
		}

		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return data != null && data.strings().contains("lunge");
	}

	/** The diamond axe (axe phase) and the diamond spear (thrown in the fang phase) are both held raised. */
	private static boolean holdsRaisedWeapon(Mob self) {
		if (self.getMainHandItem().is(Items.DIAMOND_AXE)) {
			return true;
		}

		return BuiltInRegistries.ITEM.getKey(self.getMainHandItem().getItem()).getPath().endsWith("spear");
	}
}
