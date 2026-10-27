package com.skyislands.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;

import com.skyislands.PrismarineBows;

/**
 * Another way into the same thing as PrismarineArrowRenderMixin: the entity itself is asked whether it should be drawn at all
 * (the renderers ask it first), and a Prismarine Arrow says no. Only ever asked on the client.
 */
@Mixin(Entity.class)
public abstract class PrismarineArrowEntityMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyIslands$hidePrismarineArrow(double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
		if (PrismarineBows.isHiddenArrow((Entity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
