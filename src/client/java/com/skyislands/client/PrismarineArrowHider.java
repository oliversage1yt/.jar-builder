package com.skyislands.client;

import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import com.skyislands.PrismarineBows;
import com.skyislands.SkyIslands;

/**
 * The sure way to keep the plain arrow model of a Prismarine Arrow from being drawn, in case the render hooks (the two mixins)
 * do not apply in a version: every client tick, any such arrow close by is taken out of the client's world. The server still
 * has it (the flight, the hit and the damage are all the server's); the client only loses the arrow it would have drawn,
 * and the spinning item picture, a separate entity, is untouched.
 */
final class PrismarineArrowHider {
	/** How far around the player to look (the arrows of other players' shots too). */
	private static final double RANGE = 160.0;

	private static Method removeEntity;
	private static boolean searched = false;

	private PrismarineArrowHider() { }

	static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;

		if (level == null || player == null) {
			return;
		}

		if (!searched) {
			searched = true;

			try {
				removeEntity = ClientLevel.class.getMethod("removeEntity", int.class, Entity.RemovalReason.class);
			} catch (ReflectiveOperationException e) {
				SkyIslands.LOGGER.info("Sky Islands: ClientLevel.removeEntity was not found; Prismarine Arrows are hidden by the render hooks only");
			}
		}

		if (removeEntity == null) {
			return;
		}

		List<Entity> arrows = level.getEntities(player, player.getBoundingBox().inflate(RANGE), PrismarineBows::isHiddenArrow);

		for (Entity arrow : arrows) {
			try {
				removeEntity.invoke(level, arrow.getId(), Entity.RemovalReason.DISCARDED);
			} catch (ReflectiveOperationException | RuntimeException e) {
				removeEntity = null;
				return;
			}
		}
	}
}
