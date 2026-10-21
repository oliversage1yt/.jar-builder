package com.skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import com.skyislands.CommonConfig;
import com.skyislands.Tuning;
import com.skyislands.world.IslandColumns;
import com.skyislands.world.MapMode;

/**
 * Keeps floating islands off ordinary maps.
 *
 * <ul>
 *   <li>A map remembers (in the item's custom_data, so it survives scaling, locking and copying) whether it was first used
 *       in the sky. That decides how it is drawn from then on.</li>
 *   <li><b>Normal map</b>: for columns with an island above a big air gap, the top-block search starts under the island, so the
 *       map shows the ground.</li>
 *   <li><b>Sky map</b>: only the island is drawn; columns without an island are a solid sky blue.</li>
 * </ul>
 */
@Mixin(MapItem.class)
public abstract class MapItemMixin {
	private static final String KEY = "sky_islands_map";
	private static final int GROUND_REACH = 48; // how far above the generated ground player builds (towers...) still show on a normal map

	@Inject(method = "inventoryTick", at = @At("HEAD"))
	private void skyIslands$beginTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot, CallbackInfo ci) {
		MapMode.set(skyIslands$decide(stack, level, owner));
	}

	@Inject(method = "inventoryTick", at = @At("RETURN"))
	private void skyIslands$endTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot, CallbackInfo ci) {
		MapMode.set(MapMode.NONE);
	}

	private static int skyIslands$decide(ItemStack stack, ServerLevel level, Entity owner) {
		if (!CommonConfig.skyMaps || !Level.OVERWORLD.equals(level.dimension())) {
			return MapMode.NONE;
		}

		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);

		if (existing != null) {
			CompoundTag tag = existing.copyTag();

			if (tag.contains(KEY)) {
				return tag.getBooleanOr(KEY, false) ? MapMode.SKY : MapMode.NORMAL;
			}
		}

		boolean sky = owner.getY() >= Tuning.SKY_MAP_MIN_Y;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(KEY, sky));
		return sky ? MapMode.SKY : MapMode.NORMAL;
	}

	/** Where the top-block search for one map column starts. */
	@Redirect(
			method = "update",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
	private int skyIslands$columnStart(LevelChunk chunk, Heightmap.Types type, int x, int z) {
		int vanilla = chunk.getHeight(type, x, z);
		int mode = MapMode.get();

		if (mode == MapMode.NONE) {
			return vanilla;
		}

		IslandColumns.Info info = IslandColumns.get(chunk);
		int i = (x & 15) | ((z & 15) << 4);
		boolean island = info.chainBottom[i] != IslandColumns.NONE;

		if (mode == MapMode.NORMAL) {
			return island ? Math.min(info.chainBottom[i] - 1, info.groundTop[i] + GROUND_REACH) : vanilla;
		}

		return island ? vanilla : 63; // sky map: no island means nothing to find, so a flat sky-blue column
	}

	/** The blocks the search walks through: on a sky map, everything that is not part of an island reads as sky blue. */
	@Redirect(
			method = "update",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 0))
	private BlockState skyIslands$columnBlock(LevelChunk chunk, BlockPos pos) {
		if (MapMode.get() != MapMode.SKY) {
			return chunk.getBlockState(pos);
		}

		IslandColumns.Info info = IslandColumns.get(chunk);
		int i = (pos.getX() & 15) | ((pos.getZ() & 15) << 4);

		if (info.chainBottom[i] == IslandColumns.NONE || pos.getY() < info.chainBottom[i]) {
			return Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState();
		}

		return chunk.getBlockState(pos);
	}
}
