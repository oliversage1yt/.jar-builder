package com.skyislands.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.skyislands.client.CloneProps;
import com.skyislands.client.WindBlockPose;

/**
 * Notes, for every living entity drawn, whether it is blocking with a Wind Spear (see WindBlockPose). Named by string, and the
 * arguments taken loosely, so that this mixin does not depend on how the render classes are called.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.LivingEntityRenderer")
public abstract class LivingEntityRendererBlockPoseMixin {
	@Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
	private void skyIslands$noteBlocking(@Coerce Object entity, @Coerce Object state, float partialTick, CallbackInfo ci) {
		WindBlockPose.record(entity, state);
		CloneProps.record(entity, state);
	}
}
