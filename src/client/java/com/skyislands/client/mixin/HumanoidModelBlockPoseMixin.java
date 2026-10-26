package com.skyislands.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.skyislands.client.WindBlockPose;

/**
 * After a humanoid model (the player, and the armor layers drawn on it) has been posed, puts both arms out in front if the
 * entity is blocking with a Wind Spear (see WindBlockPose).
 */
@Mixin(targets = {
		"net.minecraft.client.model.HumanoidModel",
		"net.minecraft.client.model.player.HumanoidModel"
})
public abstract class HumanoidModelBlockPoseMixin {
	@Inject(method = "setupAnim", at = @At("RETURN"), require = 0)
	private void skyIslands$blockPose(@Coerce Object state, CallbackInfo ci) {
		WindBlockPose.applyPose(this, state);
	}
}
