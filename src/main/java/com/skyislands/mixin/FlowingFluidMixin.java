package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import com.skyislands.CommonConfig;
import com.skyislands.Tuning;
import com.skyislands.world.NaturalIslands;

/**
 * Water and lava that belong to a generated island stay inside it: fluid cannot spread from a block inside an island's
 * area into a block outside it. That stops waterfalls and lava falls running down to the ground and flooding it.
 * (Fluid placed by a player in the open sky is not inside any island area, so it flows as normal.)
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
	private void skyIslands$keepInsideIsland(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo ci) {
		if (!CommonConfig.naturalFluidsContained || pos.getY() < Tuning.ISLAND_MIN_Y - 10 || !(level instanceof ServerLevel serverLevel)) {
			return;
		}

		BlockPos from = pos.relative(direction.getOpposite());

		if (NaturalIslands.isInside(serverLevel, from) && !NaturalIslands.isInside(serverLevel, pos)) {
			ci.cancel();
		}
	}
}
