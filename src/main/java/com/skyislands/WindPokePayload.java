package com.skyislands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent by the client each time the attack button is pressed with a Wind Spear in hand. The server cannot tell a poke that hit
 * nothing from no poke at all, so this is how a Riptide + Wind Burst spear knows to launch its holder. The server checks
 * everything (enchantments, cooldown, water), so the message itself carries nothing that can be trusted.
 */
public record WindPokePayload(boolean pressed) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<WindPokePayload> TYPE = new CustomPacketPayload.Type<>(SkyIslands.id("wind_poke"));
	public static final StreamCodec<RegistryFriendlyByteBuf, WindPokePayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.BOOL, WindPokePayload::pressed, WindPokePayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
