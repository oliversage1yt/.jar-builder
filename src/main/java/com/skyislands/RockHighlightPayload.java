package com.skyislands;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent to a player: the blocks (as BlockPos longs) of the fallen rock they are looking at, when it is ready to be launched
 * (whole, clear way up, not cooling down), and how many blocks up the rock will go (for the path that is shown). An empty list
 * means "no golden outline".
 */
public record RockHighlightPayload(List<Long> blocks, int rise) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RockHighlightPayload> TYPE = new CustomPacketPayload.Type<>(SkyIslands.id("rock_highlight"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RockHighlightPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), RockHighlightPayload::blocks,
					ByteBufCodecs.VAR_INT, RockHighlightPayload::rise, RockHighlightPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
