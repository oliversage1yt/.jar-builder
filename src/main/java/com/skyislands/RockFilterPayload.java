package com.skyislands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent to a player when the grayscale filter of a launched fallen rock reaches them ({@code on} true: they are close to the
 * rock) or no longer does (false: they moved away, or the rock started to fall).
 */
public record RockFilterPayload(boolean on) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RockFilterPayload> TYPE = new CustomPacketPayload.Type<>(SkyIslands.id("rock_filter"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RockFilterPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.BOOL, RockFilterPayload::on, RockFilterPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
