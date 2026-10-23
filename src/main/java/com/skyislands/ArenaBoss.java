package com.skyislands;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
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
 * <li>Worldgen (data only) swaps the chest of the {@code skyarena_fight} structure for an invisible {@code barrier} "marker" block (the {@code skyarena_chest} structure keeps its chest)
 * (see worldgen/template_pool/skyarena_fight.json). The marker is the persistent record that a boss is waiting there.</li>
 * <li>Every few ticks, chunks near players are scanned once for markers. A marker puffs black smoke; when a player comes
 * within {@link #TRIGGER_RANGE} blocks the marker is removed, a spawn sound plays and the Illusioner appears.</li>
 * <li>The boss is tagged, so it is re-adopted (boss bar, home position) after a world reload.</li>
 * <li>If it falls off the arena it is put back at the marker's position. The fight has three phases: above 2/3 health it
 * charges the player with a diamond axe (like a Vindicator), between 2/3 and 1/3 it shoots arrows with its bow, and below
 * 1/3 it drops the bow and casts evoker fangs. On death it drops diamonds, iron, gold and a random enchanted book (90%, level at least one below the maximum).</li>
 * </ul>
 */
public final class ArenaBoss {
	private static final String BOSS_TAG = "sky_islands_arena_boss";
	private static final String HOME_TAG_PREFIX = "sky_islands_home:";
	private static final String ENRAGED_TAG = "sky_islands_enraged";
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

	/** At or below this fraction of its health the boss stops charging with the axe and goes back to its bow. */
	private static final float BOW_PHASE_HEALTH = 2.0F / 3.0F;
	/** Below this fraction of its health the boss drops the bow and attacks with evoker fangs instead. */
	private static final float FANG_PHASE_HEALTH = 1.0F / 3.0F;

	/** Axe phase: damage of one swing (a diamond axe), ticks between swings, reach (squared) and run speed. */
	private static final float AXE_DAMAGE = 9.0F;
	private static final int AXE_COOLDOWN_TICKS = 20;
	/** How close (centre to centre, in blocks) the boss must be to land a swing: 1.0 blocks shorter than the original 2.5. */
	private static final double AXE_REACH = 1.5;
	private static final double AXE_REACH_SQR = AXE_REACH * AXE_REACH;
	/** Axe phase: after this long without a walkable path to the target (e.g. it pillared up) the boss shoots instead. */
	private static final int UNREACHABLE_TICKS = 40;
	/** How often (ticks) reachability is sampled. An unreachable sample adds this many ticks to the timer, a reachable one removes twice as many. */
	private static final int REACH_SAMPLE_TICKS = 4;
	/** A target this much higher than the boss (blocks) that is also this close (squared, horizontally) cannot be hit with the axe. */
	private static final double UNREACHABLE_HEIGHT = 2.0;
	private static final double UNREACHABLE_NEAR_SQR = 9.0;
	/** The boss keeps (or re-takes) its last player target while it is within this distance, even when it loses sight of it. */
	private static final double FOCUS_RANGE = 40.0;
	/** Unreachable-target bow: the arrow is released after this many ticks of drawing instead of 20 (1.5x faster). */
	private static final int FAST_DRAW_TICKS = 13;
	/** Minimum ticks the boss keeps the bow before it may go back to the axe. */
	private static final int SHOOT_MIN_TICKS = 40;
	/** Bow cooldown of the Illusioner's bow goal: vanilla value, and the faster one used while the target is out of reach. */
	private static final int BOW_INTERVAL_NORMAL = 20;
	private static final int BOW_INTERVAL_FAST = 0;
	private static final double AXE_RUN_SPEED = 0.675;

	/** Chance that an axe swing at a blocking player disables their shield, and for how long. */
	private static final float SHIELD_DISABLE_CHANCE = 0.25F;
	private static final int SHIELD_COOLDOWN_TICKS = 100;

	private static final int PHASE_AXE = 0;
	private static final int PHASE_BOW = 1;
	private static final int PHASE_FANGS = 2;
	/** Ticks between fang attacks (a vanilla Evoker waits several seconds). */
	private static final int FANG_COOLDOWN_TICKS = 24;
	private static final double FANG_RANGE = 40.0;
	/** Chance that the boss drops a random enchanted book. */
	private static final float BOOK_CHANCE = 0.9F;

	private static final String[] BOOK_ENCHANTS = {
		"protection", "fire_protection", "feather_falling", "blast_protection", "projectile_protection", "respiration",
		"aqua_affinity", "thorns", "depth_strider", "frost_walker", "soul_speed", "swift_sneak", "sharpness", "smite",
		"bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping_edge", "efficiency", "silk_touch",
		"unbreaking", "fortune", "power", "punch", "flame", "infinity", "luck_of_the_sea", "lure", "loyalty",
		"impaling", "riptide", "channeling", "multishot", "piercing", "quick_charge", "mending"
	};
	private static final int[] BOOK_ENCHANT_MAX = {
		4, 4, 4, 4, 4, 3,
		1, 3, 3, 2, 3, 3, 5, 5,
		5, 2, 2, 3, 3, 5, 1,
		3, 3, 5, 2, 1, 1, 3, 3, 3,
		5, 3, 1, 1, 4, 3, 1
	};

	private static final class Boss {
		final Mob mob;
		final ServerBossEvent bar;
		final BlockPos home;
		boolean enraged;
		int fangCooldown;
		/** -1 until the first tick, so the right weapon is (re)applied after a spawn or a world reload. */
		int phase = -1;
		int axeCooldown;
		/** The Illusioner's mirror image spell goal while it is taken away during the axe phase. */
		Object mirrorGoal;
		int mirrorPriority;
		/** Axe phase only: the target cannot be reached on foot, so the boss is shooting with a fast bow. */
		boolean shooting;
		int shootTicks;
		int unreachableTicks;
		/** The last player the boss was fighting; re-targeted when the AI drops it (e.g. a pillar blocks line of sight). */
		LivingEntity focus;

		Boss(Mob mob, ServerBossEvent bar, BlockPos home) {
			this.mob = mob;
			this.bar = bar;
			this.home = home;
		}
	}

	private static final Map<ServerLevel, Set<Long>> SCANNED = new HashMap<>();
	private static final Map<ServerLevel, Set<BlockPos>> MARKERS = new HashMap<>();
	private static final Map<UUID, Boss> BOSSES = new HashMap<>();
	private static final List<Ritual> RITUALS = new ArrayList<>();

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
		UseBlockCallback.EVENT.register(ArenaBoss::onUseBlock);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> finishRituals());

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
		RITUALS.clear();
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

		tickRituals();

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
			String command = String.format(Locale.ROOT, "summon minecraft:illusioner %.2f %.2f %.2f", x, (double) pos.getY(), z);
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
			Boss boss = new Boss(mob, bar, home);
			boss.enraged = tagsOf(mob).contains(ENRAGED_TAG);
			// phase stays -1: the first tick picks the phase from the current health and re-equips the weapon
			BOSSES.put(mob.getUUID(), boss);
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

			// Fire (soul fire in the redesigned arena, lava) never hurts the boss: a permanent, particle-free fire resistance.
			if (mob.tickCount % 40 == 0) {
				command(level, quietSource(level), "effect give " + mob.getUUID() + " minecraft:fire_resistance infinite 0 true");
			}

			keepFocus(level, boss);

			int wanted = phaseFor(mob);

			if (wanted != boss.phase) {
				enterPhase(level, boss, wanted);
			}

			if (boss.phase == PHASE_AXE) {
				tickAxe(level, boss);
			}

			if (boss.enraged && --boss.fangCooldown <= 0) {
				LivingEntity target = mob.getTarget();

				if (target != null && target.isAlive() && mob.distanceToSqr(target) <= FANG_RANGE * FANG_RANGE) {
					castFangs(level, mob, target);
					boss.fangCooldown = FANG_COOLDOWN_TICKS;
				} else {
					boss.fangCooldown = 5;
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

	// ------------------------------------------------------------------ fang phase

	private static void command(ServerLevel level, CommandSourceStack source, String command) {
		level.getServer().getCommands().performPrefixedCommand(source, command);
	}

	private static CommandSourceStack quietSource(ServerLevel level) {
		return level.getServer().createCommandSourceStack().withSuppressedOutput();
	}

	private static int phaseFor(Mob mob) {
		float health = mob.getHealth();
		float max = mob.getMaxHealth();

		if (health <= max * FANG_PHASE_HEALTH) {
			return PHASE_FANGS;
		}

		return health <= max * BOW_PHASE_HEALTH ? PHASE_BOW : PHASE_AXE;
	}

	private static void setMainHand(ServerLevel level, Mob mob, String item) {
		command(level, quietSource(level), "item replace entity " + mob.getUUID() + " weapon.mainhand with " + item);
	}

	/**
	 * Gives the boss the weapon of its new phase. The Illusioner only shoots while it holds a bow, so the axe and the
	 * empty hand both switch the arrows off.
	 */
	private static void enterPhase(ServerLevel level, Boss boss, int phase) {
		Mob mob = boss.mob;
		boolean first = boss.phase < 0;
		boss.phase = phase;
		boss.enraged = phase == PHASE_FANGS;
		boss.axeCooldown = AXE_COOLDOWN_TICKS;
		boss.fangCooldown = 20;

		// No mirror image / vanishing spell while it charges with the axe.
		setMirrorSpell(boss, phase != PHASE_AXE);

		boss.shooting = false;
		boss.unreachableTicks = 0;
		setBowInterval(boss, BOW_INTERVAL_NORMAL);

		if (phase == PHASE_AXE) {
			setMainHand(level, mob, "minecraft:diamond_axe");
		} else if (phase == PHASE_BOW) {
			setMainHand(level, mob, "minecraft:bow");
		} else {
			setMainHand(level, mob, "minecraft:air");
			mob.addTag(ENRAGED_TAG);
		}

		if (!first) {
			level.playSound(null, mob.blockPosition(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 2.0F, phase == PHASE_FANGS ? 0.7F : 1.0F);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 1.0, mob.getZ(), 40, 0.6, 0.8, 0.6, 0.05);
		}
	}

	/** First public method with this name and parameter count (reflection, so no goal classes are referenced directly). */
	private static Method findMethod(Class<?> type, String name, int parameters) {
		for (Method m : type.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == parameters) {
				m.setAccessible(true);
				return m;
			}
		}

		return null;
	}

	/**
	 * Takes the mirror image spell goal away (allowed = false) or gives it back (allowed = true). Done entirely through
	 * reflection because the goal classes and the goal selector field are not part of the public API.
	 */
	private static void setMirrorSpell(Boss boss, boolean allowed) {
		try {
			Field field = Mob.class.getDeclaredField("goalSelector");
			field.setAccessible(true);
			Object selector = field.get(boss.mob);

			if (allowed) {
				if (boss.mirrorGoal != null) {
					Method add = findMethod(selector.getClass(), "addGoal", 2);
					add.invoke(selector, boss.mirrorPriority, boss.mirrorGoal);
					boss.mirrorGoal = null;
				}
			} else if (boss.mirrorGoal == null) {
				Method getGoals = findMethod(selector.getClass(), "getAvailableGoals", 0);
				Method remove = findMethod(selector.getClass(), "removeGoal", 1);

				for (Object wrapped : new ArrayList<>((java.util.Collection<?>) getGoals.invoke(selector))) {
					Method getGoal = findMethod(wrapped.getClass(), "getGoal", 0);
					Method getPriority = findMethod(wrapped.getClass(), "getPriority", 0);
					Object goal = getGoal.invoke(wrapped);

					if (goal.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("mirror")) {
						boss.mirrorPriority = (Integer) getPriority.invoke(wrapped);
						boss.mirrorGoal = goal;
						remove.invoke(selector, goal);
						break;
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			SkyIslands.LOGGER.warn("Sky Islands could not switch the Illusioner's mirror image spell; invisibility is cleared instead", e);
		}
	}

	/**
	 * Asks the pathfinder whether the boss can walk to the target (PathNavigation.createPath / Path.canReach, by reflection
	 * so a signature change cannot break the build). Null means "cannot tell": no path came back (the pathfinder refuses
	 * to path while the mob is in the air) or the check is not possible.
	 */
	private static Boolean pathReaches(Mob mob, LivingEntity target) {
		try {
			Object navigation = mob.getNavigation();

			for (Method m : navigation.getClass().getMethods()) {
				Class<?>[] types = m.getParameterTypes();

				if (m.getName().equals("createPath") && types.length == 2 && types[0].isAssignableFrom(target.getClass()) && types[1] == int.class) {
					Object path = m.invoke(navigation, target, 1);

					if (path == null) {
						return null;
					}

					Method canReach = findMethod(path.getClass(), "canReach", 0);
					return canReach == null ? null : (Boolean) canReach.invoke(path);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}

		return null;
	}

	/**
	 * One reachability sample: 1 = the boss can get to the target, 0 = it cannot, -1 = unknown (change nothing).
	 * Standing right next to something the boss cannot climb counts as unreachable whatever the pathfinder says,
	 * because the axe cannot hit that high.
	 */
	private static int sampleReach(Mob mob, LivingEntity target) {
		double dx = target.getX() - mob.getX();
		double dz = target.getZ() - mob.getZ();
		boolean tooHigh = target.getY() - mob.getY() >= UNREACHABLE_HEIGHT;

		if (tooHigh && dx * dx + dz * dz <= UNREACHABLE_NEAR_SQR) {
			return 0;
		}

		Boolean path = pathReaches(mob, target);

		if (path == null) {
			return tooHigh ? 0 : -1;
		}

		return path ? 1 : 0;
	}

	/**
	 * The AI drops its target after a few seconds without line of sight, and a pillar between the two does exactly that.
	 * Without this the "target cannot be reached" timer kept restarting. The boss keeps fighting the last player it had.
	 */
	private static void keepFocus(ServerLevel level, Boss boss) {
		Mob mob = boss.mob;
		LivingEntity current = mob.getTarget();

		if (current != null && current.isAlive()) {
			boss.focus = current;
			return;
		}

		LivingEntity focus = boss.focus;

		if (focus == null) {
			return;
		}

		boolean valid = focus.isAlive() && !focus.isRemoved() && focus.level() == mob.level()
				&& mob.distanceToSqr(focus) <= FOCUS_RANGE * FOCUS_RANGE
				&& level.getDifficulty() != Difficulty.PEACEFUL
				&& !(focus instanceof Player p && (p.isCreative() || p.isSpectator()));

		if (valid) {
			mob.setTarget(focus);
		} else {
			boss.focus = null;
		}
	}

	/** Releases the arrow early (reflection: the method lives on the Illusioner class, whose package moved between versions). */
	private static void shootArrow(Mob mob, LivingEntity target) {
		try {
			Method shoot = findMethod(mob.getClass(), "performRangedAttack", 2);

			if (shoot != null) {
				shoot.invoke(mob, target, 1.0F);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			SkyIslands.LOGGER.warn("Sky Islands could not fire the boss's fast arrow", e);
		}
	}

	/** Sets the cooldown of the Illusioner's bow goal (reflection, as with the mirror spell). */
	private static void setBowInterval(Boss boss, int ticks) {
		try {
			Field field = Mob.class.getDeclaredField("goalSelector");
			field.setAccessible(true);
			Object selector = field.get(boss.mob);
			Method getGoals = findMethod(selector.getClass(), "getAvailableGoals", 0);

			for (Object wrapped : new ArrayList<>((java.util.Collection<?>) getGoals.invoke(selector))) {
				Object goal = findMethod(wrapped.getClass(), "getGoal", 0).invoke(wrapped);

				if (goal.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("bow")) {
					Method set = findMethod(goal.getClass(), "setMinAttackInterval", 1);

					if (set != null) {
						set.invoke(goal, ticks);
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			SkyIslands.LOGGER.warn("Sky Islands could not change the Illusioner's bow speed", e);
		}
	}

	/** Axe phase: run at the target and swing the axe like a Vindicator; shoot instead while the target is out of reach. */
	private static void tickAxe(ServerLevel level, Boss boss) {
		Mob mob = boss.mob;
		LivingEntity target = mob.getTarget();

		if (boss.axeCooldown > 0) {
			boss.axeCooldown--;
		}

		// Backup for the spell switch above: never stay invisible (and so never show clones) in the axe phase.
		if (mob.isInvisible()) {
			command(level, quietSource(level), "effect clear " + mob.getUUID() + " minecraft:invisibility");
		}

		if (target == null || !target.isAlive()) {
			boss.unreachableTicks = 0;

			if (!boss.shooting) {
				mob.setAggressive(false);
			}

			return;
		}

		if (mob.tickCount % REACH_SAMPLE_TICKS == 0) {
			int sample = sampleReach(mob, target);

			if (sample == 0) {
				boss.unreachableTicks = Math.min(UNREACHABLE_TICKS, boss.unreachableTicks + REACH_SAMPLE_TICKS);
			} else if (sample == 1) {
				boss.unreachableTicks = Math.max(0, boss.unreachableTicks - REACH_SAMPLE_TICKS * 2);
			}
		}

		if (boss.shooting) {
			// The bow goal does the aiming and strafing; here the arrow is let go early so it fires 1.5x faster.
			boss.shootTicks++;

			if (mob.isUsingItem() && mob.getTicksUsingItem() >= FAST_DRAW_TICKS && mob.hasLineOfSight(target)) {
				mob.stopUsingItem();
				shootArrow(mob, target);
			}

			// Back to the axe once the target has been reachable for a while.
			if (boss.unreachableTicks <= 0 && boss.shootTicks >= SHOOT_MIN_TICKS) {
				boss.shooting = false;
				boss.axeCooldown = AXE_COOLDOWN_TICKS;
				setBowInterval(boss, BOW_INTERVAL_NORMAL);
				setMainHand(level, mob, "minecraft:diamond_axe");
			}

			return;
		}

		if (boss.unreachableTicks >= UNREACHABLE_TICKS) {
			boss.shooting = true;
			boss.shootTicks = 0;
			mob.getNavigation().stop();
			setMainHand(level, mob, "minecraft:bow");
			setBowInterval(boss, BOW_INTERVAL_FAST);
			level.playSound(null, mob.blockPosition(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.5F, 1.2F);
			SkyIslands.LOGGER.info("Arena boss switched to its bow: target unreachable (height gap {} blocks)", String.format(Locale.ROOT, "%.1f", target.getY() - mob.getY()));
			return;
		}

		// Holding the axe counts as "aggressive", which is what makes the Illusioner's model show the item in its hand.
		mob.setAggressive(true);
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

		if (mob.tickCount % 4 == 0) {
			mob.getNavigation().moveTo(target, AXE_RUN_SPEED);
		}

		if (boss.axeCooldown <= 0 && mob.distanceToSqr(target) <= AXE_REACH_SQR && mob.hasLineOfSight(target)) {
			boss.axeCooldown = AXE_COOLDOWN_TICKS;
			swingArm(mob);

			// Checked before the hit: a raised shield still blocks the damage, like a vanilla axe hit.
			boolean blocking = target instanceof Player p && p.isBlocking();
			ItemStack shield = blocking ? ((Player) target).getUseItem().copy() : ItemStack.EMPTY;

			command(level, quietSource(level), String.format(Locale.ROOT, "damage %s %.1f minecraft:mob_attack by %s",
					target.getUUID(), AXE_DAMAGE, mob.getUUID()));

			if (blocking && mob.getRandom().nextFloat() < SHIELD_DISABLE_CHANCE) {
				disableShield(level, (Player) target, shield);
			}
		}
	}

	/** Plays the arm swing. Looked up by reflection because the signature differs between Minecraft versions. */
	private static void swingArm(Mob mob) {
		try {
			for (Method m : LivingEntity.class.getMethods()) {
				if (!m.getName().equals("swing") || m.getParameterCount() == 0 || m.getParameterTypes()[0] != InteractionHand.class) {
					continue;
				}

				Class<?>[] types = m.getParameterTypes();
				Object[] args = new Object[types.length];
				args[0] = InteractionHand.MAIN_HAND;
				boolean ok = true;

				for (int i = 1; i < types.length; i++) {
					if (types[i] == boolean.class) {
						args[i] = Boolean.TRUE;
					} else {
						ok = false;
					}
				}

				if (ok) {
					m.invoke(mob, args);
					return;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// the swing is only cosmetic
		}
	}

	/** Knocks the shield out of use and puts it on cooldown, like a vanilla axe hit that disables a shield. */
	private static void disableShield(ServerLevel level, Player player, ItemStack shield) {
		player.stopUsingItem();
		level.playSound(null, player.blockPosition(), SoundEvents.SHIELD_BREAK.value(), SoundSource.PLAYERS, 0.8F, 0.8F + level.getRandom().nextFloat() * 0.4F);

		if (shield.isEmpty()) {
			return;
		}

		try {
			Object cooldowns = player.getCooldowns();

			for (Method m : cooldowns.getClass().getMethods()) {
				if (!m.getName().equals("addCooldown") || m.getParameterCount() != 2 || m.getParameterTypes()[1] != int.class) {
					continue;
				}

				Class<?> first = m.getParameterTypes()[0];

				if (first == ItemStack.class) {
					m.invoke(cooldowns, shield, SHIELD_COOLDOWN_TICKS);
					return;
				} else if (first == shield.getItem().getClass() || first.isAssignableFrom(shield.getItem().getClass())) {
					m.invoke(cooldowns, shield.getItem(), SHIELD_COOLDOWN_TICKS);
					return;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			SkyIslands.LOGGER.warn("Sky Islands could not put the shield on cooldown", e);
		}
	}

	/** Same patterns as a vanilla Evoker: a ring or two when the target is close, otherwise a long line, but far quicker. */
	private static void castFangs(ServerLevel level, Mob mob, LivingEntity target) {
		CommandSourceStack source = quietSource(level);
		UUID id = mob.getUUID();
		String owner = String.format(Locale.ROOT, "[I;%d,%d,%d,%d]",
				(int) (id.getMostSignificantBits() >>> 32), (int) id.getMostSignificantBits(),
				(int) (id.getLeastSignificantBits() >>> 32), (int) id.getLeastSignificantBits());

		double minY = Math.min(target.getY(), mob.getY());
		double maxY = Math.max(target.getY(), mob.getY()) + 1.0;
		float angle = (float) Math.atan2(target.getZ() - mob.getZ(), target.getX() - mob.getX());

		level.playSound(null, mob.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0F, 1.2F);

		if (mob.distanceToSqr(target) < 9.0) {
			for (int i = 0; i < 5; i++) {
				float f = angle + i * (float) Math.PI * 2.0F / 5.0F + 1.2566371F;
				fang(level, source, owner, mob.getX() + Math.cos(f) * 1.5, mob.getZ() + Math.sin(f) * 1.5, minY, maxY, 0);
			}

			for (int i = 0; i < 8; i++) {
				float f = angle + i * (float) Math.PI * 2.0F / 8.0F + 1.2566371F;
				fang(level, source, owner, mob.getX() + Math.cos(f) * 2.5, mob.getZ() + Math.sin(f) * 2.5, minY, maxY, 2);
			}
		} else {
			for (int i = 0; i < 16; i++) {
				double d = 1.25 * (i + 1);
				fang(level, source, owner, mob.getX() + Math.cos(angle) * d, mob.getZ() + Math.sin(angle) * d, minY, maxY, 3 + i);
			}
		}
	}

	/** Puts one fang on the ground at (x, z), searching downward from maxY like the vanilla Evoker does. */
	private static void fang(ServerLevel level, CommandSourceStack source, String owner, double x, double z, double minY, double maxY, int warmup) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);

		for (int y = (int) Math.floor(maxY); y >= (int) Math.floor(minY) - 2; y--) {
			BlockPos pos = new BlockPos(bx, y, bz);

			if (!level.hasChunkAt(pos)) {
				return;
			}

			if (level.getBlockState(pos).isAir() && !level.getBlockState(pos.below()).isAir()) {
				command(level, source, String.format(Locale.ROOT, "summon minecraft:evoker_fangs %.2f %d %.2f {Warmup:%d,Owner:%s}", x, y, z, warmup, owner));
				return;
			}
		}
	}

	// ------------------------------------------------------------------ respawn ritual

	/** Book rises slowly and spins (ticks), then shoots up (ticks), then the beam shines while the boss stands in it (ticks). */
	private static final int RITUAL_RISE_TICKS = 70;
	private static final int RITUAL_ZOOM_TICKS = 12;
	private static final int RITUAL_BEAM_TICKS = 30;
	/** Interpolation steps of the book's animation (the client smooths each step). */
	private static final int RISE_STEP_TICKS = 5;
	private static final int ZOOM_STEP_TICKS = 3;
	/** The book floats this far above the glowing obsidian, rises this much more while it spins, then zooms up by the sum of these. */
	private static final double BOOK_START_HEIGHT = 1.6;
	private static final double BOOK_RISE_BLOCKS = 1.6;
	private static final double[] ZOOM_STEP_BLOCKS = {3.0, 7.0, 14.0, 24.0};
	/** Spin: 90 degrees per interpolation step, so the client always turns the same way. */
	private static final double SPIN_DEGREES_RISE = 18.0;
	private static final double SPIN_DEGREES_ZOOM = 30.0;
	/** The cyan glass that tints the beam sits this high above the glowing obsidian (above the boss's head). */
	private static final int BEAM_GLASS_HEIGHT = 3;

	private static final class Ritual {
		final ServerLevel level;
		final BlockPos altar;
		final UUID display = UUID.randomUUID();
		final String itemId;
		int age;
		boolean displayGone;
		boolean beamOn;
		boolean glassPlaced;
		boolean bossSpawned;

		Ritual(ServerLevel level, BlockPos altar, String itemId) {
			this.level = level;
			this.altar = altar;
			this.itemId = itemId;
		}
	}

	private static boolean isRespawnItem(ItemStack stack) {
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

		for (String allowed : CommonConfig.arenaRespawnItems.split(",")) {
			if (allowed.trim().equals(id)) {
				return true;
			}
		}

		return false;
	}

	/** Right click on the glowing obsidian with a book: starts the ritual (unless the boss is already around). */
	private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
		if (!(world instanceof ServerLevel level) || player.isSpectator() || GlowingObsidian.BLOCK == null) {
			return InteractionResult.PASS;
		}

		BlockPos altar = hit.getBlockPos();

		if (!level.getBlockState(altar).is(GlowingObsidian.BLOCK)) {
			return InteractionResult.PASS;
		}

		ItemStack stack = player.getItemInHand(hand);

		if (stack.isEmpty() || !isRespawnItem(stack)) {
			return InteractionResult.PASS;
		}

		String blocked = ritualBlocker(level, altar);

		if (blocked != null) {
			command(level, quietSource(level), "title " + player.getUUID() + " actionbar {\"text\":\"" + blocked + "\"}");
			return InteractionResult.SUCCESS;
		}

		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

		if (!player.isCreative()) {
			stack.shrink(1);
		}

		Ritual ritual = new Ritual(level, altar.immutable(), itemId);
		RITUALS.add(ritual);
		summonBook(ritual);
		return InteractionResult.SUCCESS;
	}

	/** Why the ritual cannot start right now, or null when it can. */
	private static String ritualBlocker(ServerLevel level, BlockPos altar) {
		BlockPos above = altar.above();

		if (level.getDifficulty() == Difficulty.PEACEFUL) {
			return "The altar stays silent on Peaceful.";
		}

		for (Ritual r : RITUALS) {
			if (r.level == level && r.altar.equals(altar)) {
				return "The altar is already awake.";
			}
		}

		if (isMarker(level.getBlockState(above))) {
			return "Something is already waiting here.";
		}

		for (Boss boss : BOSSES.values()) {
			if (boss.mob.isAlive() && boss.mob.level() == level
					&& Math.abs(boss.home.getX() - above.getX()) <= 3 && Math.abs(boss.home.getZ() - above.getZ()) <= 3 && Math.abs(boss.home.getY() - above.getY()) <= 3) {
				return "The Sky Illusioner is still here.";
			}
		}

		if (!level.getBlockState(above).isAir()) {
			return "Something blocks the altar.";
		}

		return null;
	}

	private static String uuidNbt(UUID id) {
		return String.format(Locale.ROOT, "[I;%d,%d,%d,%d]",
				(int) (id.getMostSignificantBits() >>> 32), (int) id.getMostSignificantBits(),
				(int) (id.getLeastSignificantBits() >>> 32), (int) id.getLeastSignificantBits());
	}

	/** The book's rise above its start height at tick t (a straight line while it rises, then the zoom steps). */
	private static double bookRise(double t) {
		if (t <= RITUAL_RISE_TICKS) {
			return BOOK_RISE_BLOCKS * t / RITUAL_RISE_TICKS;
		}

		double y = BOOK_RISE_BLOCKS;
		double left = t - RITUAL_RISE_TICKS;

		for (double step : ZOOM_STEP_BLOCKS) {
			if (left >= ZOOM_STEP_TICKS) {
				y += step;
				left -= ZOOM_STEP_TICKS;
			} else {
				return y + step * left / ZOOM_STEP_TICKS;
			}
		}

		return y;
	}

	private static double bookSpin(int t) {
		return Math.toRadians(SPIN_DEGREES_RISE * Math.min(t, RITUAL_RISE_TICKS) + SPIN_DEGREES_ZOOM * Math.max(0, t - RITUAL_RISE_TICKS));
	}

	private static String transformation(double rise, double spin) {
		return String.format(Locale.ROOT,
				"{left_rotation:[0f,%.5ff,0f,%.5ff],right_rotation:[0f,0f,0f,1f],scale:[1.5f,1.5f,1.5f],translation:[0f,%.4ff,0f]}",
				Math.sin(spin / 2.0), Math.cos(spin / 2.0), rise);
	}

	/** The floating book: an item display, so the client does the smooth movement and spinning. */
	private static void summonBook(Ritual r) {
		double x = r.altar.getX() + 0.5;
		double y = r.altar.getY() + BOOK_START_HEIGHT;
		double z = r.altar.getZ() + 0.5;

		command(r.level, quietSource(r.level), String.format(Locale.ROOT,
				"summon minecraft:item_display %.2f %.2f %.2f {UUID:%s,item:{id:\"%s\",count:1,components:{\"minecraft:enchantment_glint_override\":true}},item_display:\"ground\",brightness:{sky:15,block:15},transformation:%s}",
				x, y, z, uuidNbt(r.display), r.itemId, transformation(0.0, 0.0)));
		r.level.playSound(null, r.altar, SoundEvents.EVOKER_CAST_SPELL, SoundSource.BLOCKS, 1.0F, 1.4F);
	}

	/** Tells the book where to be in {@code steps} ticks. */
	private static void moveBook(Ritual r, int targetTick, int steps) {
		command(r.level, quietSource(r.level), String.format(Locale.ROOT,
				"data merge entity %s {start_interpolation:0,interpolation_duration:%d,transformation:%s}",
				r.display, steps, transformation(bookRise(targetTick), bookSpin(targetTick))));
	}

	private static void tickRituals() {
		Iterator<Ritual> it = RITUALS.iterator();

		while (it.hasNext()) {
			Ritual r = it.next();

			try {
				if (!r.level.hasChunkAt(r.altar)) {
					continue; // paused until the arena is loaded again, so the blocks can be put back
				}

				if (tickRitual(r)) {
					it.remove();
				}
			} catch (Throwable t) {
				SkyIslands.LOGGER.error("Sky Islands boss ritual failed; cleaning it up", t);
				it.remove();
				finishRitual(r);
			}
		}
	}

	/** Returns true when the ritual is over. */
	private static boolean tickRitual(Ritual r) {
		ServerLevel level = r.level;
		int t = r.age++;
		double cx = r.altar.getX() + 0.5;
		double cz = r.altar.getZ() + 0.5;
		double baseY = r.altar.getY() + BOOK_START_HEIGHT;
		int zoomEnd = RITUAL_RISE_TICKS + RITUAL_ZOOM_TICKS;

		// ---- animation steps
		if (t < RITUAL_RISE_TICKS && t % RISE_STEP_TICKS == 0) {
			moveBook(r, t + RISE_STEP_TICKS, RISE_STEP_TICKS);
		} else if (t >= RITUAL_RISE_TICKS && t < zoomEnd && (t - RITUAL_RISE_TICKS) % ZOOM_STEP_TICKS == 0) {
			moveBook(r, t + ZOOM_STEP_TICKS, ZOOM_STEP_TICKS);
		}

		if (t == RITUAL_RISE_TICKS) {
			level.playSound(null, r.altar, SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.BLOCKS, 1.6F, 1.5F);
		}

		// ---- particles
		if (t < RITUAL_RISE_TICKS) {
			// Enchanting-table glyphs appear all over the arena and stream into the book.
			double by = baseY + bookRise(t);

			if (t % 2 == 0) {
				for (int i = 0; i < 6; i++) {
					double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
					double radius = 2.5 + level.getRandom().nextDouble() * 8.0;
					double px = cx + Math.cos(angle) * radius;
					double pz = cz + Math.sin(angle) * radius;
					double py = r.altar.getY() + 0.8 + level.getRandom().nextDouble() * 4.0;
					level.sendParticles(ParticleTypes.ENCHANT, cx, by, cz, 0, px - cx, py - by, pz - cz, 1.0);
				}
			}
		} else if (t < zoomEnd) {
			// A trail behind the fast-moving book.
			double from = bookRise(t);
			double to = bookRise(t + 1);

			for (int i = 0; i < 3; i++) {
				double y = baseY + from + (to - from) * i / 3.0;
				level.sendParticles(ParticleTypes.END_ROD, cx, y, cz, 2, 0.12, 0.12, 0.12, 0.01);
				level.sendParticles(ParticleTypes.ENCHANT, cx, y, cz, 4, 0.3, 0.3, 0.3, 0.0);
			}
		}

		// ---- the book is gone, the beam shines and the boss is back
		if (t == zoomEnd) {
			killBook(r);
			BlockPos above = r.altar.above();
			BlockPos glass = r.altar.above(BEAM_GLASS_HEIGHT);

			if (level.getBlockState(r.altar).is(GlowingObsidian.BLOCK)) {
				// A lone beacon still shines a beam; cyan glass above the boss's head tints it.
				level.setBlock(r.altar, Blocks.BEACON.defaultBlockState(), 3);
				r.beamOn = true;

				if (level.getBlockState(glass).isAir()) {
					command(level, quietSource(level), String.format(Locale.ROOT, "setblock %d %d %d minecraft:cyan_stained_glass", glass.getX(), glass.getY(), glass.getZ()));
					r.glassPlaced = !level.getBlockState(glass).isAir();
				}
			}

			level.playSound(null, r.altar, SoundEvents.EVOKER_CAST_SPELL, SoundSource.BLOCKS, 2.0F, 0.8F);
			level.sendParticles(ParticleTypes.END_ROD, cx, above.getY() + 1.0, cz, 60, 0.5, 1.0, 0.5, 0.15);
			spawnBoss(level, above);
			r.bossSpawned = true;
		}

		if (t > zoomEnd && t % 3 == 0) {
			level.sendParticles(ParticleTypes.ENCHANT, cx, r.altar.getY() + 2.0, cz, 6, 0.6, 1.2, 0.6, 0.0);
		}

		if (t >= zoomEnd + RITUAL_BEAM_TICKS) {
			finishRitual(r);
			return true;
		}

		return false;
	}

	private static void killBook(Ritual r) {
		if (!r.displayGone) {
			r.displayGone = true;
			command(r.level, quietSource(r.level), "kill " + r.display);
		}
	}

	/** Cleans up a ritual (also used when the world is closing mid-ritual): book gone, blocks restored, boss present. */
	private static void finishRitual(Ritual r) {
		killBook(r);

		if (r.beamOn && r.level.getBlockState(r.altar).is(Blocks.BEACON)) {
			r.level.setBlock(r.altar, GlowingObsidian.BLOCK.defaultBlockState(), 3);
		}

		BlockPos glass = r.altar.above(BEAM_GLASS_HEIGHT);

		if (r.glassPlaced && BuiltInRegistries.BLOCK.getKey(r.level.getBlockState(glass).getBlock()).getPath().equals("cyan_stained_glass")) {
			r.level.setBlock(glass, Blocks.AIR.defaultBlockState(), 3);
		}

		r.beamOn = false;
		r.glassPlaced = false;

		if (!r.bossSpawned) {
			r.bossSpawned = true;
			spawnBoss(r.level, r.altar.above());
		}
	}

	private static void finishRituals() {
		for (Ritual r : new ArrayList<>(RITUALS)) {
			try {
				if (r.level.hasChunkAt(r.altar)) {
					finishRitual(r);
				}
			} catch (Throwable t) {
				SkyIslands.LOGGER.warn("Sky Islands could not finish a boss ritual while the world was closing", t);
			}
		}

		RITUALS.clear();
	}

	// ------------------------------------------------------------------ drops

	private static void onDeath(ServerLevel level, LivingEntity entity) {
		Boss boss = BOSSES.remove(entity.getUUID());

		if (boss != null) {
			boss.bar.removeAllPlayers();
		}

		// Rolled here rather than from a loot table: the loot table's count/enchant functions were being ignored.
		var random = level.getRandom();
		double x = entity.getX();
		double y = entity.getY() + 0.5;
		double z = entity.getZ();

		drop(level, x, y, z, new ItemStack(Items.DIAMOND, 5 + random.nextInt(6)));
		drop(level, x, y, z, new ItemStack(Items.IRON_INGOT, 3 + random.nextInt(6)));
		drop(level, x, y, z, new ItemStack(Items.GOLD_INGOT, 5 + random.nextInt(8)));

		if (random.nextFloat() < BOOK_CHANCE) {
			int pick = random.nextInt(BOOK_ENCHANTS.length);
			int max = BOOK_ENCHANT_MAX[pick];
			// Never below max - 1 (sharpness is 4 or 5); single-level enchantments are always level 1.
			int enchantLevel = max <= 1 ? 1 : (max - 1) + random.nextInt(2);
			String command = String.format(Locale.ROOT,
					"summon minecraft:item %.2f %.2f %.2f {Item:{id:\"minecraft:enchanted_book\",count:1,components:{\"minecraft:stored_enchantments\":{\"minecraft:%s\":%d}}}}",
					x, y, z, BOOK_ENCHANTS[pick], enchantLevel);
			command(level, quietSource(level), command);
		}
	}

	private static void drop(ServerLevel level, double x, double y, double z, ItemStack stack) {
		level.addFreshEntity(new ItemEntity(level, x, y, z, stack));
	}
}
