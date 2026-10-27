package com.skyislands.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;

import com.skyislands.PrismarineBows;

/**
 * Leaves the plain arrow model of a Prismarine Arrow undrawn: the arrow is shown as a spinning item picture instead (a
 * server-side item display, see SpearVisual). Both the dispatcher and the renderer are given, whichever the version asks.
 */
@Mixin(targets = {
		"net.minecraft.client.renderer.entity.EntityRenderDispatcher",
		"net.minecraft.client.renderer.entity.EntityRenderer"
})
public abstract class PrismarineArrowRenderMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyIslands$hidePrismarineArrow(Entity entity, @Coerce Object frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
		if (PrismarineBows.isHiddenArrow(entity)) {
			cir.setReturnValue(false);
		}
	}
}
