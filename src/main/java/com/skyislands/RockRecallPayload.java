package com.skyislands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent to the player who launched a fallen rock, at each stage of its flight, so their client can play the matching sounds:
 * the looping "perform" sound while the rock rises, and the "finish" sound when it starts to fall.
 */
public record RockRecallPayload(int event) implements CustomPacketPayload {
	/** The rock has been launched and starts to rise. */
	public static final int RISE = 0;
	/** The rock has reached the top of the rise and hangs there. */
	public static final int PEAK = 1;
	/** The rock starts to fall back down. */
	public static final int FALL = 2;
	/** The flight is over for any reason (landed, put back, cut short): any sound that is still going should stop. */
	public static final int END = 3;

	public static final CustomPacketPayload.Type<RockRecallPayload> TYPE = new CustomPacketPayload.Type<>(SkyIslands.id("rock_recall"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RockRecallPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, RockRecallPayload::event, RockRecallPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
