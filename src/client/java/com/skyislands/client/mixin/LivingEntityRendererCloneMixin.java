package com.skyislands.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.skyislands.client.CloneProps;

/**
 * After a living entity's model has been drawn (the Illusioner draws itself once for each of its images while it is
 * invisible), draws the boss's weapons on it (see CloneProps). Named by string, with the arguments other than the pose stack
 * taken loosely, so that this mixin does not depend on how the render classes are called.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.LivingEntityRenderer")
public abstract class LivingEntityRendererCloneMixin {
	@Inject(method = "submit", at = @At("RETURN"), require = 0)
	private void skyIslands$cloneProps(@Coerce Object state, PoseStack poseStack, @Coerce Object collector, @Coerce Object camera, CallbackInfo ci) {
		CloneProps.render(state, poseStack, collector);
	}
}
