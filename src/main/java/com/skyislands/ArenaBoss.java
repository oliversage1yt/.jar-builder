package com.skyislands;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/**
 * The sky arena boss: a beefed-up Illusioner.
 *
 * <p>How it fits together:
 * <ul>
 * <li>Worldgen (data only) swaps the arena's chest for an invisible {@code barrier} "marker" block half of the time
 * (see worldgen/template_pool/skyarena.json). The marker is the persistent record that a boss is waiting there.</li>
 * <li>Every few ticks, chunks near players are scanned once for markers. A marker puffs black smoke; when a player comes
 * within {@link #TRIGGER_RANGE} blocks the marker is removed, a spawn sound plays and the Illusioner appears.</li>
 * <li>The boss is tagged, so it is re-adopted (boss bar, home position) after a world reload.</li>
 * <li>If it falls off the arena it is put back at the marker's position. On death it drops
 * data/sky_islands/loot_table/entities/arena_boss.json.</li>
 * </ul>
 */
public final class ArenaBoss {
	private static final String BOSS_TAG = "sky_islands_arena_boss";
	private static final String HOME_TAG_PREFIX = "sky_islands_home:";
	private static final String LOOT_TABLE = "sky_islands:entities/arena_boss";
	private static final String BOSS_NAME = "Sky Illusioner";

	/** A player this close to the smoke wakes the boss. */
	private static final double TRIGGER_RANGE = 5.0;
	/** Smoke is shown to players within this range (also how close chunks are scanned: SCAN_CHUNK_RADIUS). */
	private static final double SMOKE_RANGE = 48.0;
	private static final int SCAN_CHUNK_RADIUS = 3;
	/** The boss bar is shown to players this close to the boss. */
	private static final double BAR_RANGE = 80.0;
	/** The boss is sent home when it is this far below, or this far (horizontally) from, its home position. */
	private static final double FALL_BELOW_HOME = 3.0;
	private static final double MAX_DISTANCE_FROM_HOME = 26.0;

	private static final class Boss {
		final Mob mob;
		final ServerBossEvent bar;
		final BlockPos home;

		Boss(Mob mob, ServerBossEvent bar, BlockPos home) {
			this.mob = mob;
			this.bar = bar;
			this.home = home;
		}
	}

	private static final Map<ServerLevel, Set<Long>> SCANNED = new HashMap<>();
	private static final Map<ServerLevel, Set<BlockPos>> MARKERS = new HashMap<>();
	private static final Map<UUID, Boss> BOSSES = new HashMap<>();

	/** Set only while the summon command runs: the arena position the new Illusioner belongs to. */
	private static BlockPos pendingHome;
	private static boolean pendingAdopted;

	private static Method tagsMethod;
	private static boolean tagsLookedUp;

	private static int tickCounter;
	private static boolean markersFailed;
	private static boolean bossesFailed;

	private ArenaBoss() { }

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ArenaBoss::tick);

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!(entity instanceof Mob mob)) {
				return;
			}

			if (pendingHome != null && !pendingAdopted && isIllusioner(entity)
					&& entity.distanceToSqr(pendingHome.getX() + 0.5, pendingHome.getY() + 0.5, pendingHome.getZ() + 0.5) < 9.0) {
				// The Illusioner that the summon command in spawnBoss() just created.
				pendingAdopted = true;
				adopt(mob, pendingHome);
			} else if (tagsOf(entity).contains(BOSS_TAG)) {
				// A boss coming back after a world reload.
				BlockPos home = parseHome(entity);
				track(mob, home != null ? home : mob.blockPosition());
			}
		});

		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			Boss boss = BOSSES.remove(entity.getUUID());

			if (boss != null) {
				boss.bar.removeAllPlayers();
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (tagsOf(entity).contains(BOSS_TAG) && entity.level() instanceof ServerLevel level) {
				onDeath(level, entity);
			}
		});
	}

	public static void clear() {
		SCANNED.clear();
		MARKERS.clear();

		for (Boss boss : BOSSES.values()) {
			boss.bar.removeAllPlayers();
		}

		BOSSES.clear();
	}

	// ------------------------------------------------------------------ ticking

	private static void tick(MinecraftServer server) {
		tickCounter++;

		if (!markersFailed) {
			try {
				if (tickCounter % 10 == 0) {
					scanNearPlayers(server);
				}

				if (tickCounter % 5 == 0) {
					tickMarkers();
				}
			} catch (Throwable t) {
				markersFailed = true;
				SkyIslands.LOGGER.error("Sky Islands arena boss spawning failed and was disabled for this session", t);
			}
		}

		if (!bossesFailed) {
			try {
				tickBosses();
			} catch (Throwable t) {
				bossesFailed = true;
				SkyIslands.LOGGER.error("Sky Islands arena boss upkeep failed and was disabled for this session", t);
			}
		}
	}

	// ------------------------------------------------------------------ markers

	private static boolean isMarker(BlockState state) {
		return state.is(Blocks.BARRIER);
	}

	/** Looks once at every loaded chunk close to a player. */
	private static void scanNearPlayers(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.players().isEmpty()) {
				continue;
			}

			Set<Long> done = SCANNED.computeIfAbsent(level, l -> new HashSet<>());

			for (ServerPlayer player : level.players()) {
				int pcx = player.blockPosition().getX() >> 4;
				int pcz = player.blockPosition().getZ() >> 4;

				for (int dx = -SCAN_CHUNK_RADIUS; dx <= SCAN_CHUNK_RADIUS; dx++) {
					for (int dz = -SCAN_CHUNK_RADIUS; dz <= SCAN_CHUNK_RADIUS; dz++) {
						int cx = pcx + dx;
						int cz = pcz + dz;
						long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);

						if (done.contains(key)) {
							continue;
						}

						BlockPos corner = new BlockPos(cx << 4, 64, cz << 4);

						if (!level.hasChunkAt(corner)) {
							continue;
						}

						done.add(key);
						scanChunk(level, level.getChunkAt(corner));
					}
				}
			}
		}
	}

	private static void scanChunk(ServerLevel level, LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];

			// Cheap palette check first: almost every section can be skipped without reading a single block.
			if (section == null || section.hasOnlyAir() || !section.maybeHas(ArenaBoss::isMarker)) {
				continue;
			}

			int baseY = chunk.getSectionYFromSectionIndex(i) << 4;

			for (int x = 0; x < 16; x++) {
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						if (isMarker(section.getBlockState(x, y, z))) {
							MARKERS.computeIfAbsent(level, l -> new HashSet<>()).add(new BlockPos(minX + x, baseY + y, minZ + z));
						}
					}
				}
			}
		}
	}

	/** Smoke at every known marker, and the spawn check. */
	private static void tickMarkers() {
		for (Map.Entry<ServerLevel, Set<BlockPos>> entry : MARKERS.entrySet()) {
			ServerLevel level = entry.getKey();
			Iterator<BlockPos> it = entry.getValue().iterator();

			while (it.hasNext()) {
				BlockPos pos = it.next();

				if (!level.hasChunkAt(pos)) {
					continue;
				}

				if (!isMarker(level.getBlockState(pos))) {
					it.remove();
					continue;
				}

				double x = pos.getX() + 0.5;
				double y = pos.getY() + 0.3;
				double z = pos.getZ() + 0.5;
				boolean someoneNear = false;
				boolean triggered = false;

				for (ServerPlayer player : level.players()) {
					double d = player.distanceToSqr(x, y, z);

					if (d <= SMOKE_RANGE * SMOKE_RANGE) {
						someoneNear = true;
					}

					if (d <= TRIGGER_RANGE * TRIGGER_RANGE && player.isAlive() && !player.isSpectator()) {
						triggered = true;
					}
				}

				if (!someoneNear) {
					continue;
				}

				level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.4, z, 6, 0.35, 0.3, 0.35, 0.004);

				// The boss never appears on Peaceful (it would just despawn); the smoke waits.
				if (triggered && level.getDifficulty() != Difficulty.PEACEFUL) {
					it.remove();
					spawnBoss(level, pos);
				}
			}
		}
	}

	// ------------------------------------------------------------------ boss

	private static void spawnBoss(ServerLevel level, BlockPos pos) {
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;

		level.playSound(null, pos, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.5F, 1.1F);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 40, 0.6, 0.6, 0.6, 0.05);

		// A plain vanilla summon (so the Illusioner gets its bow and normal setup); ENTITY_LOAD adopts it as the boss.
		pendingHome = pos.immutable();
		pendingAdopted = false;

		try {
			MinecraftServer server = level.getServer();
			CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
			String command = String.format(Locale.ROOT, "summon minecraft:illusioner %.2f %.2f %.2f", x, pos.getY(), z);
			server.getCommands().performPrefixedCommand(source, command);

			if (!pendingAdopted) {
				SkyIslands.LOGGER.warn("Could not spawn the arena boss at {}", pos);
			}
		} finally {
			pendingHome = null;
		}
	}

	private static boolean isIllusioner(Entity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("illusioner");
	}

	/** Turns a freshly summoned Illusioner into the boss. */
	private static void adopt(Mob boss, BlockPos home) {
		var maxHealth = boss.getAttribute(Attributes.MAX_HEALTH);

		if (maxHealth != null) {
			maxHealth.setBaseValue(CommonConfig.arenaBossHealth);
		}

		boss.setHealth(boss.getMaxHealth());
		boss.setCustomName(Component.literal(BOSS_NAME));
		boss.setPersistenceRequired();
		boss.addTag(BOSS_TAG);
		boss.addTag(HOME_TAG_PREFIX + home.getX() + ":" + home.getY() + ":" + home.getZ());
		track(boss, home.immutable());
	}

	/** Entity tags, looked up by name because the getter was renamed between Minecraft versions. */
	@SuppressWarnings("unchecked")
	private static Set<String> tagsOf(Entity entity) {
		if (!tagsLookedUp) {
			tagsLookedUp = true;

			for (String name : new String[] {"getTags", "entityTags", "tags"}) {
				try {
					Method m = Entity.class.getMethod(name);

					if (Set.class.isAssignableFrom(m.getReturnType())) {
						tagsMethod = m;
						break;
					}
				} catch (NoSuchMethodException ignored) {
					// try the next name
				}
			}

			if (tagsMethod == null) {
				SkyIslands.LOGGER.error("Sky Islands could not find the entity tag getter; arena bosses will not survive a reload");
			}
		}

		if (tagsMethod != null) {
			try {
				return (Set<String>) tagsMethod.invoke(entity);
			} catch (ReflectiveOperationException e) {
				return Set.of();
			}
		}

		return Set.of();
	}

	/** Builds the boss bar with whichever constructor this Minecraft version has (with or without a UUID). */
	private static ServerBossEvent createBar() throws ReflectiveOperationException {
		Component name = Component.literal(BOSS_NAME);

		for (Constructor<?> c : ServerBossEvent.class.getConstructors()) {
			Class<?>[] types = c.getParameterTypes();
			Object[] args = new Object[types.length];
			boolean ok = true;

			for (int i = 0; i < types.length && ok; i++) {
				Class<?> t = types[i];

				if (t == UUID.class) {
					args[i] = UUID.randomUUID();
				} else if (t.isEnum()) {
					args[i] = null;

					for (Object constant : t.getEnumConstants()) {
						String n = ((Enum<?>) constant).name();

						if (n.equals("PURPLE") || n.equals("NOTCHED_10")) {
							args[i] = constant;
						}
					}

					ok = args[i] != null;
				} else if (t.isInstance(name)) {
					args[i] = name;
				} else {
					ok = false;
				}
			}

			if (ok) {
				return (ServerBossEvent) c.newInstance(args);
			}
		}

		throw new IllegalStateException("No usable ServerBossEvent constructor");
	}

	private static void track(Mob mob, BlockPos home) {
		if (BOSSES.containsKey(mob.getUUID())) {
			return;
		}

		try {
			ServerBossEvent bar = createBar();
			bar.setProgress(1.0F);
			BOSSES.put(mob.getUUID(), new Boss(mob, bar, home));
		} catch (ReflectiveOperationException | RuntimeException e) {
			SkyIslands.LOGGER.error("Sky Islands could not create the arena boss bar; the boss will fight without one", e);
		}
	}

	private static BlockPos parseHome(Entity entity) {
		for (String tag : tagsOf(entity)) {
			if (tag.startsWith(HOME_TAG_PREFIX)) {
				String[] parts = tag.substring(HOME_TAG_PREFIX.length()).split(":");

				if (parts.length == 3) {
					try {
						return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
					} catch (NumberFormatException ignored) {
						return null;
					}
				}
			}
		}

		return null;
	}

	private static void tickBosses() {
		Iterator<Boss> it = BOSSES.values().iterator();

		while (it.hasNext()) {
			Boss boss = it.next();
			Mob mob = boss.mob;

			if (!mob.isAlive() || mob.isRemoved() || !(mob.level() instanceof ServerLevel level)) {
				boss.bar.removeAllPlayers();
				it.remove();
				continue;
			}

			boss.bar.setProgress(Math.max(0.0F, Math.min(1.0F, mob.getHealth() / mob.getMaxHealth())));

			for (ServerPlayer player : level.players()) {
				if (player.distanceToSqr(mob) <= BAR_RANGE * BAR_RANGE) {
					boss.bar.addPlayer(player);
				} else {
					boss.bar.removePlayer(player);
				}
			}

			double dx = mob.getX() - (boss.home.getX() + 0.5);
			double dz = mob.getZ() - (boss.home.getZ() + 0.5);
			boolean fell = mob.getY() < boss.home.getY() - FALL_BELOW_HOME;
			boolean wandered = dx * dx + dz * dz > MAX_DISTANCE_FROM_HOME * MAX_DISTANCE_FROM_HOME;

			if (fell || wandered) {
				level.sendParticles(ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 1.0, mob.getZ(), 20, 0.4, 0.6, 0.4, 0.03);
				mob.setPos(boss.home.getX() + 0.5, boss.home.getY(), boss.home.getZ() + 0.5);
				mob.setDeltaMovement(Vec3.ZERO);
				mob.resetFallDistance();
				mob.getNavigation().stop();
				level.playSound(null, boss.home, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.8F);
				level.sendParticles(ParticleTypes.LARGE_SMOKE, boss.home.getX() + 0.5, boss.home.getY() + 1.0, boss.home.getZ() + 0.5, 20, 0.4, 0.6, 0.4, 0.03);
			}
		}
	}

	private static void onDeath(ServerLevel level, LivingEntity entity) {
		Boss boss = BOSSES.remove(entity.getUUID());

		if (boss != null) {
			boss.bar.removeAllPlayers();
		}

		// Loot comes from a data-driven table, so amounts can be tuned without touching code.
		MinecraftServer server = level.getServer();
		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		String command = String.format(Locale.ROOT, "loot spawn %.2f %.2f %.2f loot %s", entity.getX(), entity.getY() + 0.5, entity.getZ(), LOOT_TABLE);
		server.getCommands().performPrefixedCommand(source, command);
	}
}
