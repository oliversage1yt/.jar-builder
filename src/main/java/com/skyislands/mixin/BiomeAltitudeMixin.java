package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

import com.skyislands.Tuning;

/**
 * Stops altitude from cooling the air at island height.
 *
 * <p>Vanilla lowers a position's temperature the higher it is above y=80, so islands in mild/cool biomes (taiga, oceans,
 * birch forest, cherry grove ...) get snow and ice. Here, every temperature-based snow/ice/rain check receives a position
 * clamped to y=80 whenever the real position is above {@link Tuning#SNOW_FIX_MIN_Y}. The biome's own base temperature is
 * untouched, so naturally freezing biomes (and the glaciated islands that spawn in them) still snow.
 *
 * <p>The target method names are listed together because the exact private helper name can differ between versions.
 * Any name that does not exist is skipped ({@code require = 0}); if none match the mixin simply does nothing.
 */
@Mixin(Biome.class)
public abstract class BiomeAltitudeMixin {
	@ModifyVariable(
			method = {"coldEnoughToSnow", "warmEnoughToRain", "getHeightAdjustedTemperature", "getTemperature"},
			at = @At("HEAD"),
			argsOnly = true,
			ordinal = 0,
			require = 0)
	private BlockPos skyIslands$flattenAltitude(BlockPos pos) {
		if (pos.getY() >= Tuning.SNOW_FIX_MIN_Y) {
			return new BlockPos(pos.getX(), Tuning.SNOW_FIX_CLAMP_Y, pos.getZ());
		}

		return pos;
	}
}
