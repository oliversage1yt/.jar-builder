package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

/**
 * Makes the Sky Illusioner hold its diamond axe (or the diamond spear it throws in the last phase) like a Vindicator (arms
 * raised, weapon visible) instead of the bow-aiming pose. The target is named by string, with both package names Minecraft has used for the Illusioner, so that a version
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

		if (!self.isAggressive() || !holdsRaisedWeapon(self)) {
			return;
		}

		Object[] poses = original.getDeclaringClass().getEnumConstants();

		for (Object pose : poses) {
			if (pose instanceof Enum<?> e && e.name().equals("ATTACKING")) {
				cir.setReturnValue(e);
				return;
			}
		}
	}

	/** The diamond axe (axe phase) and the diamond spear (thrown in the fang phase) are both held raised. */
	private static boolean holdsRaisedWeapon(Mob self) {
		if (self.getMainHandItem().is(Items.DIAMOND_AXE)) {
			return true;
		}

		return BuiltInRegistries.ITEM.getKey(self.getMainHandItem().getItem()).getPath().equals("diamond_spear");
	}
}
