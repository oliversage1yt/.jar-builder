package com.skyislands;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent to a player when this mod unlocks recipes for them: the item ids (such as "sky_islands:prismarine_axe") to show in the popup. */
public record RecipeUnlockPayload(List<String> items) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RecipeUnlockPayload> TYPE = new CustomPacketPayload.Type<>(SkyIslands.id("recipe_unlock"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RecipeUnlockPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), RecipeUnlockPayload::items, RecipeUnlockPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
