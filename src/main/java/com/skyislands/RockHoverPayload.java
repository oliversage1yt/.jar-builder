package com.skyislands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent by the client when the block under the player's crosshair changes (the crosshair target is already limited to normal
 * block reach). {@code has} is false when the crosshair is not on a block; {@code pos} is then ignored. The server answers
 * with a {@link RockHighlightPayload} when that block is part of a fallen rock that is ready to be launched.
 */
public record RockHoverPayload(boolean has, long pos) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RockHoverPayload> TYPE = new CustomPacketPayload.Type<>(SkyIslands.id("rock_hover"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RockHoverPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.BOOL, RockHoverPayload::has, ByteBufCodecs.VAR_LONG, RockHoverPayload::pos, RockHoverPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
