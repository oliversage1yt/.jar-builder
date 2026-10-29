package com.skyislands;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent to a player: the blocks (as BlockPos longs) of the fallen rock they are looking at, when it is ready to be launched
 * (whole, clear way up, not cooling down). An empty list means "no golden outline".
 */
public record RockHighlightPayload(List<Long> blocks) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RockHighlightPayload> TYPE = new CustomPacketPayload.Type<>(SkyIslands.id("rock_highlight"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RockHighlightPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), RockHighlightPayload::blocks, RockHighlightPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
