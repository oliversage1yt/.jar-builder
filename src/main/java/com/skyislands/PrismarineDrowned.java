package com.skyislands;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.item.ItemStack;

/**
 * Drowned that carry a Prismarine Bow and shoot Prismarine Arrows.
 *
 * <ul>
 * <li><b>Spawning</b>: a drowned that spawns naturally with nothing in its main hand has a {@link #BOW_CHANCE} chance to get a
 * Prismarine Bow. A vanilla drowned gets a trident about 6.25% of the time, so this is twice as likely as a trident. It is
 * rolled on top of the vanilla equipment (a drowned that already has a trident or a fishing rod is left alone), so tridents
 * are as common as before. Drowned made by a command, a spawn egg, a spawner or a zombie drowning, and the rain drowned of
 * {@code RainDrowned}, are not touched.</li>
 * <li><b>Shooting</b>: a drowned holding the bow gets an extra goal (added when it is spawned or loaded; goals are not saved).
 * While its target is in range and in sight it stands still, draws the bow ({@link #DRAW_TICKS} ticks, the bow's pull pictures
 * show) and shoots a Prismarine Arrow ({@code PrismarineBows.shootFromMob}), then waits a bit and does it again. Too close, too
 * far or out of sight, it does what a drowned always does (walks up and hits). It goes after whoever the drowned's own AI
 * picked as its target, so the usual drowned rules hold: a player on land is only hunted at night or in water.</li>
 * </ul>
 */
final class PrismarineDrowned {
	/** Twice a vanilla drowned's chance (6.25%) to spawn with a trident. */
	static final double BOW_CHANCE = 0.125;

	/** Starts shooting between these distances (blocks) and keeps going between the wider ones. */
	private static final double START_MIN_RANGE = 5.0;
	private static final double START_MAX_RANGE = 24.0;
	private static final double KEEP_MIN_RANGE = 3.5;
	private static final double KEEP_MAX_RANGE = 28.0;
	/** Ticks from raising the bow to letting go (the Prismarine Bow's own full draw is 26). */
	private static final int DRAW_TICKS = 26;
	/** The wait after a shot: at least this, and up to RELOAD_EXTRA_TICKS more. */
	private static final int RELOAD_TICKS = 25;
	private static final int RELOAD_EXTRA_TICKS = 25;
	/** Without a clear line to the target for this long, the goal gives up (the usual AI takes over). */
	private static final int LOST_SIGHT_TICKS = 20;

	/** The drowned that already have the goal (it has to be added again whenever the drowned is loaded as a new object). */
	private static final Map<Mob, Boolean> HAS_GOAL = new WeakHashMap<>();
	private static Field goalSelectorField;

	private PrismarineDrowned() { }

	static void register() {
		// a new drowned is made just before it joins the world, which is where its equipment can still be added
		ServerEntityEvents.ALLOW_LOAD.register((entity, level, spawnReason, loadedFromDisk) -> {
			maybeGiveBow(entity, spawnReason, loadedFromDisk);
			return true;
		});

		// every drowned that joins the world holding the bow (new, or loaded from a saved chunk) gets the shooting goal
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> addShootingGoal(entity));
	}

	static void clear() {
		HAS_GOAL.clear();
	}

	private static boolean isDrowned(Entity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("drowned");
	}

	private static void maybeGiveBow(Entity entity, EntitySpawnReason spawnReason, boolean loadedFromDisk) {
		if (loadedFromDisk || spawnReason != EntitySpawnReason.NATURAL || PrismarineBows.PRISMARINE_BOW == null
				|| !(entity instanceof Mob mob) || !isDrowned(entity)) {
			return;
		}

		if (!mob.getMainHandItem().isEmpty() || ArenaBoss.tagsOf(mob).contains(RainDrowned.TAG)) {
			return;
		}

		if (mob.getRandom().nextDouble() < BOW_CHANCE) {
			mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(PrismarineBows.PRISMARINE_BOW));
		}
	}

	private static void addShootingGoal(Entity entity) {
		if (PrismarineBows.PRISMARINE_BOW == null || !(entity instanceof Mob mob) || !isDrowned(entity)
				|| !mob.getMainHandItem().is(PrismarineBows.PRISMARINE_BOW) || HAS_GOAL.containsKey(mob)) {
			return;
		}

		try {
			if (goalSelectorField == null) {
				goalSelectorField = Mob.class.getDeclaredField("goalSelector");
				goalSelectorField.setAccessible(true);
			}

			// priority 1: ahead of the drowned's own attack goals (2), so that this one wins while it is able to run
			((GoalSelector) goalSelectorField.get(mob)).addGoal(1, new BowAttackGoal(mob));
			HAS_GOAL.put(mob, Boolean.TRUE);
		} catch (ReflectiveOperationException | RuntimeException e) {
			SkyIslands.LOGGER.warn("Sky Islands could not give a drowned its bow goal; it holds the bow but only fights in melee", e);
		}
	}

	/** Draw, shoot, wait, repeat, while the target is in range and in sight. Takes over movement and looking from the drowned's other goals. */
	private static final class BowAttackGoal extends Goal {
		private final Mob mob;
		private int drawn;
		private int cooldown;
		private int lostSight;

		BowAttackGoal(Mob mob) {
			this.mob = mob;
			setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
		}

		private boolean holdsBow() {
			return mob.getMainHandItem().is(PrismarineBows.PRISMARINE_BOW);
		}

		@Override
		public boolean canUse() {
			LivingEntity target = mob.getTarget();

			if (target == null || !target.isAlive() || !holdsBow()) {
				return false;
			}

			double distance = mob.distanceTo(target);
			return distance >= START_MIN_RANGE && distance <= START_MAX_RANGE && mob.hasLineOfSight(target);
		}

		public boolean canContinueToUse() {
			LivingEntity target = mob.getTarget();

			if (target == null || !target.isAlive() || !holdsBow()) {
				return false;
			}

			double distance = mob.distanceTo(target);
			return distance >= KEEP_MIN_RANGE && distance <= KEEP_MAX_RANGE && lostSight < LOST_SIGHT_TICKS;
		}

		public void start() {
			drawn = 0;
			cooldown = 10;
			lostSight = 0;
			mob.setAggressive(true);
		}

		public void stop() {
			if (mob.isUsingItem()) {
				mob.stopUsingItem();
			}

			drawn = 0;
			lostSight = 0;
			mob.setAggressive(false);
		}

		public void tick() {
			LivingEntity target = mob.getTarget();

			if (target == null || !(mob.level() instanceof ServerLevel level)) {
				return;
			}

			boolean sees = mob.hasLineOfSight(target);
			lostSight = sees ? 0 : lostSight + 1;

			mob.getNavigation().stop();
			mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

			if (!sees) {
				// no shot: lower the bow until the target is in sight again
				if (mob.isUsingItem()) {
					mob.stopUsingItem();
				}

				drawn = 0;
				return;
			}

			if (cooldown > 0) {
				cooldown--;
				return;
			}

			if (!mob.isUsingItem()) {
				mob.startUsingItem(InteractionHand.MAIN_HAND);
				drawn = 0;
			}

			drawn++;

			if (drawn >= DRAW_TICKS) {
				mob.stopUsingItem();
				PrismarineBows.shootFromMob(level, mob, target);
				drawn = 0;
				cooldown = RELOAD_TICKS + mob.getRandom().nextInt(RELOAD_EXTRA_TICKS + 1);
			}
		}
	}
}
