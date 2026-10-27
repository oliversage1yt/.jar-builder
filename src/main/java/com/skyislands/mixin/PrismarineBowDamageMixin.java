package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import com.skyislands.PrismarineBows;

/**
 * An arrow shot from the Prismarine Bow does twice the damage: the hit is dealt again, as a hit of double the size, in place
 * of the original one. The arrow's own damage (Power, Impaling, a critical hit) is already in the number by now, so all of it is
 * doubled. {@link PrismarineBows#doubling} stops the repeated hit from being doubled again.
 */
@Mixin(LivingEntity.class)
public abstract class PrismarineBowDamageMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyIslands$doublePrismarineBowDamage(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (PrismarineBows.doubling || !PrismarineBows.firedFromPrismarineBow(source.getDirectEntity())) {
			return;
		}

		PrismarineBows.doubling = true;

		try {
			cir.setReturnValue(((LivingEntity) (Object) this).hurtServer(level, source, amount * PrismarineBows.BOW_DAMAGE_MULTIPLIER));
		} finally {
			PrismarineBows.doubling = false;
		}
	}
}
