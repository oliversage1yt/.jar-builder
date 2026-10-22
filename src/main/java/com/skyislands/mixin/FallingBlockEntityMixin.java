package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;

import com.skyislands.CommonConfig;
import com.skyislands.Tuning;
import com.skyislands.world.NaturalIslands;

/**
 * Sand, gravel and other falling blocks that fall off a naturally generated island vanish once they drop below the sky island height (Y=140), instead of raining down and piling up on the ground. A block that started outside any island (for example one a
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
			BlockPos start = self.getStartPos();
			// Check where the block started, and the spots just under/over it, so edge blocks of an island still count.
			boolean natural = NaturalIslands.isInside(serverLevel, start)
					|| NaturalIslands.isInside(serverLevel, start.below())
					|| NaturalIslands.isInside(serverLevel, start.above());
			this.skyIslands$natural = (byte) (natural ? 1 : 0);
		}

		if (this.skyIslands$natural == 1 && this.getY() < Tuning.ISLAND_CUTOFF_Y) {
			this.discard();
		}
	}
}
