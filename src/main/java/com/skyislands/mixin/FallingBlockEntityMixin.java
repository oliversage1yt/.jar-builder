package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;

import com.skyislands.CommonConfig;
import com.skyislands.Tuning;
import com.skyislands.world.NaturalIslands;

/**
 * Sand, gravel and other falling blocks that fall off a naturally generated island vanish once they drop below the island
 * band, instead of raining down and piling up on the ground. A block that started outside any island (for example one a
 * player placed in the open sky) falls and lands as normal.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin extends Entity {
	@Unique
	private byte skyIslands$natural = -1; // -1 not checked yet, 0 no, 1 yes

	public FallingBlockEntityMixin(EntityType<?> type, Level level) {
		super(type, level);
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void skyIslands$vanishBelowIslands(CallbackInfo ci) {
		if (!CommonConfig.naturalFallingBlocksVanish || !(this.level() instanceof ServerLevel serverLevel)) {
			return;
		}

		if (this.skyIslands$natural < 0) {
			FallingBlockEntity self = (FallingBlockEntity) (Object) this;
			this.skyIslands$natural = (byte) (NaturalIslands.isInside(serverLevel, self.getStartPos()) ? 1 : 0);
		}

		if (this.skyIslands$natural == 1 && this.getY() < Tuning.ISLAND_CUTOFF_Y) {
			this.discard();
		}
	}
}
