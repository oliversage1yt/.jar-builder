package com.skyislands.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;

import com.skyislands.SkyIslands;
import com.skyislands.WindBlockPayload;
import com.skyislands.WindPokePayload;

/** Tells the server when the player holds attack and use together with a Wind Spear in use (the block). */
final class WindBlockInput {
	/** Horizontal movement kept each tick while blocking: about a fifth of the normal walking speed. */
	private static final double BLOCK_DAMPING = 0.35;

	private static boolean sending;
	private static boolean attackWasDown;
	private static int ticks;

	private WindBlockInput() { }

	static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		// Slowness while the server confirms the block (its flag is on the spear): the walking speed is cut by damping the
		// player's own horizontal movement. A speed attribute modifier was used first, but it also narrows the field of
		// view, which was the zoom.
		if (player != null && player.isUsingItem() && isWindSpear(player.getUseItem()) && blockFlag(player.getUseItem())) {
			Vec3 motion = player.getDeltaMovement();
			player.setDeltaMovement(motion.x * BLOCK_DAMPING, motion.y, motion.z * BLOCK_DAMPING);
		}

		// A poke that hits nothing never reaches the server, so each fresh press of attack with a Wind Spear in hand is
		// reported (the server decides whether it launches anything: Riptide + Wind Burst).
		boolean attackDown = player != null && client.gui.screen() == null && client.options.keyAttack.isDown();

		if (attackDown && !attackWasDown && isWindSpear(player.getMainHandItem())) {
			ClientPlayNetworking.send(new WindPokePayload(true));
		}

		attackWasDown = attackDown;

		boolean held = player != null && client.gui.screen() == null && player.isUsingItem()
				&& client.options.keyAttack.isDown() && client.options.keyUse.isDown() && isWindSpear(player.getUseItem());

		if (held) {
			// a heartbeat every 2 ticks, so a lost "let go" message cannot leave the block on
			if (!sending || ++ticks % 2 == 0) {
				ClientPlayNetworking.send(new WindBlockPayload(true));
			}

			sending = true;
		} else if (sending) {
			sending = false;
			ticks = 0;
			ClientPlayNetworking.send(new WindBlockPayload(false));
		}
	}

	private static boolean blockFlag(ItemStack stack) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return data != null && !data.flags().isEmpty() && Boolean.TRUE.equals(data.flags().get(0));
	}

	private static boolean isWindSpear(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return key.getNamespace().equals(SkyIslands.MOD_ID) && key.getPath().endsWith("_windspear");
	}
}
