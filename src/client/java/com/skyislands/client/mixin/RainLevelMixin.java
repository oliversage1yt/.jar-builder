package com.skyislands.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

import com.skyislands.client.RainAboveClouds;

/**
 * Hides the rain above the clouds by scaling the rain strength at the moment it is read, rather than overwriting the
 * level's stored value once per tick. The server's weather packets are handled between ticks, so overwriting the value
 * left a frame or two showing the real rain on every packet (which come every tick while the weather changes): that was
 * the flicker of the rain and the sky colour.
 */
@Mixin(Level.class)
public abstract class RainLevelMixin {
	@Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
	private void skyIslands$hideRainAboveClouds(float partialTick, CallbackInfoReturnable<Float> cir) {
		if ((Object) this instanceof ClientLevel level) {
			RainAboveClouds.mixinActive = true;
			float factor = RainAboveClouds.factor(level);

			if (factor < 1.0F) {
				cir.setReturnValue(cir.getReturnValueF() * factor);
			}
		}
	}
}
