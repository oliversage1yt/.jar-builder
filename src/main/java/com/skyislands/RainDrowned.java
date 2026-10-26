package com.skyislands;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.skyislands.world.NaturalIslands;

/**
 * Rain drowned: during rain and thunderstorms, drowned holding a copper Wind Spear now and then spawn on any sky island
 * near a player, whatever the island's height. Vanilla drowned refuse to hunt players in daylight (and do not walk or hit
 * then), so this class does the hunting itself: it picks the nearest player as the target, walks the drowned up, pokes
 * with the spear when close and throws it (after a short wind-up) from a distance. The drowned cannot burn while it
 * rains; the moment the rain stops they vanish in a puff of smoke.
 */
final class RainDrowned {
	static final String TAG = "sky_islands_rain_drowned";
	static final String GUARDIAN_TAG = "sky_islands_rain_guardian";
	private static final String SHOW_TRIDENT_NAME = "sky_islands_show_trident";

	private static final int SPAWN_CHECK_TICKS = 40;
	private static final int THROW_CHECK_TICKS = 4;
	private static final double SPAWN_CHANCE = 0.30;
	private static final int SPAWN_TRIES = 10;
	private static final double GUARDIAN_CHANCE = 0.25;
	private static final int GUARDIAN_TRIES = 30;
	private static final int GUARDIAN_MAX = 3;
	private static final double MIN_THROW_DISTANCE = 5.0;
	private static final double MAX_THROW_DISTANCE = 20.0;

	private static final double CHASE_RANGE = 32.0;
	private static final double POKE_REACH = 2.6;
	private static final int POKE_COOLDOWN_TICKS = 20;
	private static final int WINDUP_TICKS = 12;
	private static final int CLEANUP_CHECK_TICKS = 20;

	private static final Map<UUID, Integer> NEXT_THROW = new HashMap<>();
	/** Drowned that are winding up a throw -> the tick the spear leaves their hand. */
	private static final Map<UUID, Integer> RELEASE_AT = new HashMap<>();
	private static final Map<UUID, Integer> NEXT_POKE = new HashMap<>();
	/** The last tick each drowned could see the player it hunts. */
	private static final Map<UUID, Integer> LAST_SEEN = new HashMap<>();
	/** Like a zombie, it gives up the chase after losing sight of the player for this long (3 seconds). */
	private static final int LOSE_SIGHT_TICKS = 60;

	private RainDrowned() { }

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(RainDrowned::tick);

		// While it rains by day, no hostile mob spawns naturally in the sky island height range (drowned excepted).
		// Night is unchanged, and so are mobs placed by commands, spawners, structures and world generation.
		ServerEntityEvents.ALLOW_LOAD.register((entity, level, spawnReason, loadedFromDisk) -> !(!loadedFromDisk
				&& spawnReason == EntitySpawnReason.NATURAL
				&& entity.getType().getCategory() == MobCategory.MONSTER
				&& !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("drowned")
				&& Level.OVERWORLD.equals(level.dimension())
				&& entity.getY() >= Tuning.ISLAND_MIN_Y - 10
				&& level.isRaining()
				&& !isNight(level)));

		// the trident a drowned shows while winding up a throw is only for looks: if it drops, it is removed
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ItemEntity item) {
				Component name = item.getItem().get(DataComponents.CUSTOM_NAME);

				if (name != null && name.getString().equals(SHOW_TRIDENT_NAME)) {
					item.discard();
				}
			}
		});
	}

	static void clear() {
		NEXT_THROW.clear();
		RELEASE_AT.clear();
		NEXT_POKE.clear();
		LAST_SEEN.clear();
	}

	private static void tick(MinecraftServer server) {
		if (!CommonConfig.rainDrowned) {
			return;
		}

		ServerLevel level = server.overworld();
		int now = server.getTickCount();

		if (level == null || level.players().isEmpty()) {
			return;
		}

		boolean raining = level.isRaining();

		if (now % THROW_CHECK_TICKS == 0) {
			huntTick(level, now, raining);
		}

		if (!raining && now % CLEANUP_CHECK_TICKS == 0) {
			cleanupTick(level);
		}

		if (now % SPAWN_CHECK_TICKS == 0 && level.isRaining()) {
			spawnTick(level);
		}
	}

	// ------------------------------------------------------------------ hunting

	/** Once the rain stops every rain drowned vanishes in a puff of smoke (also ones that were out of sight when it stopped). */
	private static void cleanupTick(ServerLevel level) {
		Set<UUID> seen = new HashSet<>();

		for (ServerPlayer player : level.players()) {
			for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(160.0, 512.0, 160.0))) {
				if (mob.isAlive() && seen.add(mob.getUUID()) && isRainMob(mob)) {
					poof(level, mob);
				}
			}
		}
	}

	private static void poof(ServerLevel level, Mob mob) {
		double x = mob.getX();
		double y = mob.getY() + mob.getBbHeight() * 0.5;
		double z = mob.getZ();

		level.sendParticles(ParticleTypes.POOF, x, y, z, 24, 0.3, 0.5, 0.3, 0.05);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 12, 0.3, 0.5, 0.3, 0.02);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:entity.generic.extinguish_fire hostile @a %.1f %.1f %.1f 1.0 1.2", x, y, z));

		NEXT_THROW.remove(mob.getUUID());
		RELEASE_AT.remove(mob.getUUID());
		NEXT_POKE.remove(mob.getUUID());
		LAST_SEEN.remove(mob.getUUID());
		mob.discard();
	}

	private static void huntTick(ServerLevel level, int now, boolean raining) {
		Set<UUID> seen = new HashSet<>();

		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}

			for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(40.0, 64.0, 40.0))) {
				if (!mob.isAlive() || !seen.add(mob.getUUID()) || !ArenaBoss.tagsOf(mob).contains(TAG)) {
					continue;
				}

				if (!raining) {
					poof(level, mob);
					continue;
				}

				// no burning while it rains (a drowned on a high island is above the rain and would catch fire in daylight)
				mob.clearFire();
				hunt(level, mob, now);
			}
		}

		if (NEXT_THROW.size() > 256) {
			NEXT_THROW.values().removeIf(value -> value < now - 200);
		}

		if (NEXT_POKE.size() > 256) {
			NEXT_POKE.values().removeIf(value -> value < now - 200);
		}
	}

	/**
	 * Vanilla drowned only hunt players at night or when the player is in water, and their melee goal switches off in
	 * daylight too, so a rain drowned would just stand there. This sets the target itself, walks up, pokes and throws.
	 */
	private static void hunt(ServerLevel level, Mob mob, int now) {
		UUID id = mob.getUUID();
		LivingEntity target = mob.getTarget();
		ServerPlayer victim = null;

		// Like a zombie: it notices a player only when it can see them, and chases for a few seconds after losing sight.
		if (target instanceof ServerPlayer current && !current.isSpectator() && !current.isCreative() && current.isAlive()
				&& mob.distanceTo(current) <= CHASE_RANGE) {
			if (mob.hasLineOfSight(current)) {
				LAST_SEEN.put(id, now);
			}

			if (now - LAST_SEEN.getOrDefault(id, -1000) <= LOSE_SIGHT_TICKS) {
				victim = current;
			}
		}

		if (victim == null) {
			victim = nearestVisiblePlayer(level, mob);

			if (victim != null) {
				LAST_SEEN.put(id, now);
			} else if (target != null) {
				mob.getNavigation().stop();
			}

			mob.setTarget(victim);
		}

		if (victim == null) {
			mob.setAggressive(false);
			RELEASE_AT.remove(id);
			holdSpear(mob);
			return;
		}

		mob.setAggressive(true);
		double distance = mob.distanceTo(victim);
		boolean sees = mob.hasLineOfSight(victim);

		// winding up a throw: stand still, face the player, then let go
		Integer release = RELEASE_AT.get(id);

		if (release != null) {
			if (!sees || distance < MIN_THROW_DISTANCE || distance > MAX_THROW_DISTANCE) {
				RELEASE_AT.remove(id);
				holdSpear(mob);
			} else {
				mob.getNavigation().stop();
				mob.getLookControl().setLookAt(victim, 60.0F, 60.0F);

				if (release <= now) {
					RELEASE_AT.remove(id);
					holdSpear(mob);
					NEXT_THROW.put(id, now + 60 + level.getRandom().nextInt(50));
					WindSpears.throwFromMob(level, mob, victim);
				}

				return;
			}
		}

		if (!RELEASE_AT.containsKey(id)) {
			holdSpear(mob);
		}

		// close enough: poke with the spear
		if (distance <= POKE_REACH && sees) {
			mob.getNavigation().stop();
			mob.getLookControl().setLookAt(victim, 60.0F, 60.0F);

			if (NEXT_POKE.getOrDefault(id, 0) <= now) {
				NEXT_POKE.put(id, now + POKE_COOLDOWN_TICKS);
				WindSpears.swingArm(mob);
				level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getEyeY() - 0.3, victim.getZ(), 6, 0.2, 0.2, 0.2, 0.1);
				ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
						"damage %s %.1f minecraft:mob_attack by %s", victim.getUUID(),
						(float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE), id));
			}

			return;
		}

		// within throwing range with a clear shot: start the wind-up when the throw is due
		if (distance >= MIN_THROW_DISTANCE && distance <= MAX_THROW_DISTANCE && sees) {
			Integer next = NEXT_THROW.get(id);

			if (next == null) {
				// first sighting: a short wait before the first throw
				NEXT_THROW.put(id, now + 20 + level.getRandom().nextInt(30));
			} else if (next <= now) {
				RELEASE_AT.put(id, now + WINDUP_TICKS);
				holdTrident(mob);
				mob.getNavigation().stop();
				return;
			}
		}

		// otherwise walk toward the player
		mob.getNavigation().moveTo(victim, 1.0);
	}

	/**
	 * A drowned only shows the throwing arm when it holds a real trident (a vanilla model check), so during the wind-up it
	 * holds a trident that only exists for the picture; the real spear is flown by WindSpears.
	 */
	private static void holdTrident(Mob mob) {
		if (mob.getMainHandItem().is(Items.TRIDENT)) {
			return;
		}

		ItemStack trident = new ItemStack(Items.TRIDENT);
		trident.set(DataComponents.CUSTOM_NAME, Component.literal(SHOW_TRIDENT_NAME));
		mob.setItemSlot(EquipmentSlot.MAINHAND, trident);
	}

	/** Puts the copper Wind Spear back in the drowned's hand (also when a wind-up was cut short or the drowned was reloaded mid wind-up). */
	private static void holdSpear(Mob mob) {
		if (!mob.getMainHandItem().is(Items.TRIDENT)) {
			return;
		}

		mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(BuiltInRegistries.ITEM.getValue(SkyIslands.id("copper_windspear"))));
	}

	private static ServerPlayer nearestVisiblePlayer(ServerLevel level, Mob mob) {
		ServerPlayer best = null;
		double bestDistance = CHASE_RANGE * CHASE_RANGE;

		for (ServerPlayer player : level.players()) {
			if (player.isSpectator() || player.isCreative() || !player.isAlive()) {
				continue;
			}

			double distance = mob.distanceToSqr(player);

			if (distance < bestDistance && mob.hasLineOfSight(player)) {
				bestDistance = distance;
				best = player;
			}
		}

		return best;
	}

	// ------------------------------------------------------------------ spawning

	private static void spawnTick(ServerLevel level) {
		if (!WindSpears.registered("copper")) {
			return;
		}

		double chance = SPAWN_CHANCE * (level.isThundering() ? 2.0 : 1.0);
		int max = CommonConfig.rainDrownedMax + (level.isThundering() ? 2 : 0);

		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}

			boolean drownedRoll = level.getRandom().nextDouble() < chance;
			boolean guardianRoll = level.getRandom().nextDouble() < GUARDIAN_CHANCE;

			if (!drownedRoll && !guardianRoll) {
				continue;
			}

			int drowned = 0;
			int guardians = 0;

			for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(96.0, 128.0, 96.0))) {
				if (!mob.isAlive()) {
					continue;
				}

				if (ArenaBoss.tagsOf(mob).contains(TAG)) {
					drowned++;
				} else if (ArenaBoss.tagsOf(mob).contains(GUARDIAN_TAG)) {
					guardians++;
				}
			}

			if (drownedRoll && drowned < max) {
				trySpawn(level, player);
			}

			if (guardianRoll && guardians < GUARDIAN_MAX) {
				trySpawnGuardian(level, player);
			}
		}
	}

	/** Guardians spawn in water on the sky islands (pools, rivers) while it rains. */
	private static void trySpawnGuardian(ServerLevel level, ServerPlayer player) {
		int top = player.getBlockY() + 48;
		int bottom = Math.max(Tuning.ISLAND_MIN_Y - 10, player.getBlockY() - 64);

		for (int attempt = 0; attempt < GUARDIAN_TRIES; attempt++) {
			double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
			double distance = 10.0 + level.getRandom().nextDouble() * 30.0;
			int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);

			for (int y = top; y >= bottom; y--) {
				BlockPos pos = new BlockPos(x, y, z);

				if (!level.hasChunkAt(pos)) {
					break;
				}

				BlockState state = level.getBlockState(pos);

				// look down through air, leaves and plants to the first thing that is solid or water
				if (state.getFluidState().isEmpty() && (state.isAir() || state.is(BlockTags.LEAVES) || state.getCollisionShape(level, pos).isEmpty())) {
					continue;
				}

				// water with water on at least two sides: a pool or a river wide enough for a guardian
				if (state.getFluidState().is(FluidTags.WATER)) {
					int sides = (isWater(level, pos.north()) ? 1 : 0) + (isWater(level, pos.south()) ? 1 : 0)
							+ (isWater(level, pos.east()) ? 1 : 0) + (isWater(level, pos.west()) ? 1 : 0);

					if (sides >= 2) {
						ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
								"summon minecraft:guardian %.2f %.2f %.2f {Tags:[\"%s\"]}", x + 0.5, y + 0.05, z + 0.5, GUARDIAN_TAG));
						return;
					}
				}

				break;
			}
		}
	}

	private static boolean isWater(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
	}

	private static boolean isRainMob(Mob mob) {
		var tags = ArenaBoss.tagsOf(mob);
		return tags.contains(TAG) || tags.contains(GUARDIAN_TAG);
	}

	private static Method dayTimeMethod;
	private static Method brightMethod;
	private static boolean timeLookedUp;

	/**
	 * Night, for the natural spawn rule. The time of day is looked up by name (the world clock changed between versions);
	 * if neither the day time nor the brightness can be read, it counts as day, so nothing hostile spawns in the rain.
	 */
	private static boolean isNight(ServerLevel level) {
		if (!timeLookedUp) {
			timeLookedUp = true;

			try {
				dayTimeMethod = Level.class.getMethod("getDayTime");
			} catch (ReflectiveOperationException ignored) {
				// try the brightness instead
			}

			try {
				brightMethod = Level.class.getMethod("isBrightOutside");
			} catch (ReflectiveOperationException ignored) {
				// no brightness either
			}
		}

		try {
			if (dayTimeMethod != null) {
				long time = ((Number) dayTimeMethod.invoke(level)).longValue() % 24000L;
				return time >= 13000L && time <= 23000L;
			}

			if (brightMethod != null) {
				return !((Boolean) brightMethod.invoke(level));
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// treat it as day
		}

		return false;
	}

	private static void trySpawn(ServerLevel level, ServerPlayer player) {
		int top = player.getBlockY() + 48;
		int bottom = Math.max(Tuning.ISLAND_MIN_Y - 10, player.getBlockY() - 64);

		for (int attempt = 0; attempt < SPAWN_TRIES; attempt++) {
			double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
			double distance = 16.0 + level.getRandom().nextDouble() * 24.0;
			int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);

			for (int y = top; y >= bottom; y--) {
				BlockPos pos = new BlockPos(x, y, z);

				if (!level.hasChunkAt(pos)) {
					break;
				}

				BlockState below = level.getBlockState(pos.below());

				if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
						|| below.isAir() || !below.getFluidState().isEmpty()) {
					continue;
				}

				// the first standing spot from the top of this column: it has to be bare island ground under the open sky.
				// Any island height will do: a drowned is allowed even where the island is above the rain itself.
				if (!below.is(BlockTags.LEAVES) && !below.getCollisionShape(level, pos.below()).isEmpty()
						&& level.canSeeSky(pos) && NaturalIslands.isInside(level, pos.below())) {
					spawn(level, x + 0.5, y, z + 0.5);
					return;
				}

				break;
			}
		}
	}

	private static void spawn(ServerLevel level, double x, double y, double z) {
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"summon minecraft:drowned %.2f %.2f %.2f {Tags:[\"%s\"],equipment:{mainhand:{id:\"sky_islands:copper_windspear\",count:1}}}",
				x, y, z, TAG));
	}
}
