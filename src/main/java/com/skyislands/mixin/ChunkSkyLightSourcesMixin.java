package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;

import com.skyislands.CommonConfig;
import com.skyislands.Tuning;
import com.skyislands.world.LightIslands;

/**
 * Makes floating islands transparent to sky light, so the ground under them is not in shadow.
 *
 * <p>Minecraft keeps, for every column, a "lowest sky source" height: everything at or above it counts as open sky and
 * everything below is only lit by light spreading in from the sides. An island creates a source edge at its top, so the
 * ground beneath never gets direct sky light. For columns that have an island above a big air gap, this starts the
 * search for that height below the island instead, as if the island were not there.
 *
 * <p>Side effect: the inside of island buildings counts as open sky too, so they are fully lit.
 */
@Mixin(ChunkSkyLightSources.class)
public abstract class ChunkSkyLightSourcesMixin {
	@Shadow
	@Final
	private int minY;

	@Shadow
	private static boolean isEdgeOccluded(BlockState topState, BlockState bottomState) {
		throw new AssertionError();
	}

	@Inject(method = "findLowestSourceY", at = @At("HEAD"), cancellable = true)
	private void skyIslands$skipIsland(ChunkAccess chunk, int topSectionIndex, int x, int z, CallbackInfoReturnable<Integer> cir) {
		if (!CommonConfig.lightThroughIslands) {
			return;
		}

		int[] island = new int[2];

		if (!LightIslands.scan(chunk, x, z, island)) {
			return;
		}

		int startY = island[0] - 1; // the first air block under the island
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState topState = Blocks.AIR.defaultBlockState();
		int topY = startY;

		for (int y = startY - 1; y >= this.minY; y--) {
			BlockState bottomState = chunk.getBlockState(pos.set(x, y, z));

			if (isEdgeOccluded(topState, bottomState)) {
				cir.setReturnValue(topY);
				return;
			}

			topState = bottomState;
			topY = y;
		}

		cir.setReturnValue(this.minY);
	}

	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void skyIslands$ignoreIslandEdits(BlockGetter level, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (!CommonConfig.lightThroughIslands || y < Tuning.ISLAND_MIN_Y - 2 || !(level instanceof ChunkAccess chunk)) {
			return;
		}

		int[] island = new int[2];

		// a block placed or broken inside the island does not change what the ground below sees
		if (LightIslands.scan(chunk, x, z, island) && y >= island[0] - 1) {
			cir.setReturnValue(false);
		}
	}
}
