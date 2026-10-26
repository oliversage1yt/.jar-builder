package com.skyislands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent by the client to the server while the player holds the attack button together with the use button (the server
 * cannot see the attack button while an item is being used). {@code holding} is false once the attack button is let go.
 */
public record WindBlockPayload(boolean holding) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<WindBlockPayload> TYPE = new CustomPacketPayload.Type<>(SkyIslands.id("wind_block"));
	public static final StreamCodec<RegistryFriendlyByteBuf, WindBlockPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.BOOL, WindBlockPayload::holding, WindBlockPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
