package com.skyislands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/**
 * A Prismarine Arrow thrown by hand (right click with it, see {@code PrismarineBows.PrismarineArrowItem}).
 *
 * <ul>
 * <li>It hurts as much as a stone sword (read from the vanilla stone sword's attack damage; {@link #FALLBACK_DAMAGE}, which is
 * 5, if that cannot be read).</li>
 * <li>It flies straight for only {@link #STRAIGHT_RANGE} blocks, then loses its forward speed and drops.</li>
 * <li>There is <b>no cooldown</b>: it is thrown on every click, so a player can spam them at a target for quick damage. One
 * arrow is used up per throw (none in creative mode), and the arrow shatters where it lands, like the shot ones.</li>
 * <li>Water does not slow it, like a shot Prismarine Arrow (there is no drag at all in this class).</li>
 * </ul>
 *
 * <p>The flight is worked out here, with no arrow entity (the way the Wind Spears fly, see {@code WindSpears}); it is drawn with
 * the same spinning item picture as a shot arrow ({@link SpearVisual#spawnItem}).
 */
final class ThrownPrismarineArrows {
	/** Blocks per tick (a shot arrow from a full draw does about 3). */
	private static final double SPEED = 1.6;
	/** How far it flies in a straight line before it starts to fall. */
	private static final double STRAIGHT_RANGE = 8.0;
	/** After the straight part: the pull down (blocks per tick, every tick) and how much sideways speed is left per tick. */
	private static final double FALL_GRAVITY = 0.06;
	private static final double FALL_DRAG = 0.85;
	/** A thrown arrow that has not hit anything is gone after this many ticks. */
	private static final int MAX_AGE = 80;
	/** The damage if the stone sword's damage cannot be read (a stone sword: 4 attack damage + 1 for the hand). */
	private static final float FALLBACK_DAMAGE = 5.0F;

	private static final List<Flying> FLYING = new ArrayList<>();
	private static Float stoneSwordDamage;

	private ThrownPrismarineArrows() { }

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> tick());
	}

	static void clear() {
		FLYING.clear();
		stoneSwordDamage = null;
	}

	/** What a stone sword does: its attack damage plus the base 1 (read once, when the first arrow is thrown, as item data is bound by then). */
	private static float damage() {
		if (stoneSwordDamage == null) {
			float found = FALLBACK_DAMAGE;

			try {
				ItemAttributeModifiers modifiers = new ItemStack(Items.STONE_SWORD).getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
				double total = 0.0;

				for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
					if (entry.attribute().equals(Attributes.ATTACK_DAMAGE)) {
						total += entry.modifier().amount();
					}
				}

				if (total > 0.0) {
					found = (float) (1.0 + total);
				}
			} catch (RuntimeException ignored) {
				// the fallback is a stone sword's damage anyway
			}

			stoneSwordDamage = found;
		}

		return stoneSwordDamage;
	}

	/** Throws one Prismarine Arrow from the stack the player holds (the stack is the one in their hand: one arrow is taken from it). */
	static void throwArrow(ServerLevel level, ServerPlayer player, ItemStack stack) {
		if (!player.isCreative()) {
			stack.shrink(1);
		}

		double yaw = Math.toRadians(player.getYRot());
		double pitch = Math.toRadians(player.getXRot());
		double dx = -Math.sin(yaw) * Math.cos(pitch);
		double dy = -Math.sin(pitch);
		double dz = Math.cos(yaw) * Math.cos(pitch);

		double x = player.getX() + dx * 0.6;
		double y = player.getEyeY() - 0.2 + dy * 0.6;
		double z = player.getZ() + dz * 0.6;

		Flying arrow = new Flying(level, player.getUUID(), x, y, z, dx * SPEED, dy * SPEED, dz * SPEED);
		// made right now, in the middle of the player's own click: it goes out to the players with the next update, at once
		arrow.visual = SpearVisual.spawnItem(level, PrismarineBows.VISUAL_ITEM, PrismarineBows.VISUAL_SCALE,
				PrismarineBows.TIP_X, PrismarineBows.TIP_Y, x, y, z, dx, dy, dz);
		FLYING.add(arrow);

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:entity.arrow.shoot player @a %.1f %.1f %.1f 0.7 1.5", player.getX(), player.getY(), player.getZ()));
	}

	private static final class Flying {
		final ServerLevel level;
		final UUID owner;
		final Set<UUID> hit = new HashSet<>();
		UUID visual;
		double x;
		double y;
		double z;
		double vx;
		double vy;
		double vz;
		double lastFreeX;
		double lastFreeY;
		double lastFreeZ;
		/** How far it has flown, and for how many ticks. */
		double travelled;
		int age;

		Flying(ServerLevel level, UUID owner, double x, double y, double z, double vx, double vy, double vz) {
			this.level = level;
			this.owner = owner;
			this.x = x;
			this.y = y;
			this.z = z;
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			this.lastFreeX = x;
			this.lastFreeY = y;
			this.lastFreeZ = z;
		}
	}

	private static void tick() {
		if (FLYING.isEmpty()) {
			return;
		}

		Iterator<Flying> it = FLYING.iterator();

		while (it.hasNext()) {
			Flying arrow = it.next();

			if (!advance(arrow)) {
				it.remove();
				SpearVisual.remove(arrow.level, arrow.visual);
			}
		}
	}

	/** One tick of flight. Returns false when the arrow is finished (it hit something, broke, or ran out of time). */
	private static boolean advance(Flying arrow) {
		ServerLevel level = arrow.level;
		arrow.age++;

		if (arrow.age > MAX_AGE) {
			PrismarineBows.shatterAt(level, arrow.x, arrow.y, arrow.z);
			return false;
		}

		// past the straight part it loses its forward speed and falls
		if (arrow.travelled >= STRAIGHT_RANGE) {
			arrow.vy -= FALL_GRAVITY;
			arrow.vx *= FALL_DRAG;
			arrow.vz *= FALL_DRAG;
		}

		double length = Math.sqrt(arrow.vx * arrow.vx + arrow.vy * arrow.vy + arrow.vz * arrow.vz);
		int steps = Math.max(1, (int) Math.ceil(length / 0.4));

		for (int i = 1; i <= steps; i++) {
			double px = arrow.x + arrow.vx * i / steps;
			double py = arrow.y + arrow.vy * i / steps;
			double pz = arrow.z + arrow.vz * i / steps;
			BlockPos block = new BlockPos((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));

			if (!level.hasChunkAt(block)) {
				return false;
			}

			BlockState state = level.getBlockState(block);

			if (!state.getCollisionShape(level, block).isEmpty()) {
				// it breaks on the block, a little before the surface
				PrismarineBows.shatterAt(level, arrow.lastFreeX, arrow.lastFreeY, arrow.lastFreeZ);
				return false;
			}

			FluidState fluid = state.getFluidState();

			if (!fluid.isEmpty() && i == steps && arrow.age % 2 == 0) {
				level.sendParticles(ParticleTypes.BUBBLE, px, py, pz, 2, 0.05, 0.05, 0.05, 0.01);
			}

			arrow.lastFreeX = px;
			arrow.lastFreeY = py;
			arrow.lastFreeZ = pz;

			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(px - 1.5, py - 1.5, pz - 1.5, px + 1.5, py + 1.5, pz + 1.5))) {
				if (target.getUUID().equals(arrow.owner) || !target.isAlive() || arrow.hit.contains(target.getUUID())
						|| (target instanceof Player other && other.isSpectator())) {
					continue;
				}

				if (!target.getBoundingBox().inflate(0.3).contains(px, py, pz)) {
					continue;
				}

				arrow.hit.add(target.getUUID());
				hitEntity(arrow, target, px, py, pz);
				return false;
			}
		}

		arrow.x += arrow.vx;
		arrow.y += arrow.vy;
		arrow.z += arrow.vz;
		arrow.travelled += length;
		SpearVisual.moveItem(level, arrow.visual, arrow.x, arrow.y, arrow.z, arrow.vx, arrow.vy, arrow.vz, arrow.age,
				PrismarineBows.TIP_X, PrismarineBows.TIP_Y);
		return true;
	}

	private static void hitEntity(Flying arrow, LivingEntity target, double x, double y, double z) {
		ServerLevel level = arrow.level;
		PrismarineBows.shatterAt(level, x, y, z);
		level.sendParticles(ParticleTypes.CRIT, x, y, z, 6, 0.2, 0.2, 0.2, 0.2);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:entity.arrow.hit player @a %.1f %.1f %.1f 1.0 1.2", x, y, z));

		Entity owner = level.getEntity(arrow.owner);
		String by = owner != null ? " by " + arrow.owner : "";
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"damage %s %.1f minecraft:arrow%s", target.getUUID(), damage(), by));
	}
}
