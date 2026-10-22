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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * <li>If it falls off the arena it is put back at the marker's position. Below a third of its health it drops the bow and
 * casts evoker fangs instead. On death it drops diamonds, iron, gold and sometimes a random enchanted book.</li>
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

	/** Below this fraction of its health the boss drops the bow and attacks with evoker fangs instead. */
	private static final float FANG_PHASE_HEALTH = 1.0F / 3.0F;
	/** Ticks between fang attacks (a vanilla Evoker waits several seconds). */
	private static final int FANG_COOLDOWN_TICKS = 24;
	private static final double FANG_RANGE = 40.0;
	/** Chance that the boss drops a random enchanted book. */
	private static final float BOOK_CHANCE = 0.4F;

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

			if (!boss.enraged && mob.getHealth() <= mob.getMaxHealth() * FANG_PHASE_HEALTH) {
				enrage(level, boss);
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

	/** Switches the boss from the bow to evoker fangs. */
	private static void enrage(ServerLevel level, Boss boss) {
		Mob mob = boss.mob;
		boss.enraged = true;
		boss.fangCooldown = 20;
		mob.addTag(ENRAGED_TAG);

		// With no bow in hand the Illusioner's ranged attack stops; the fangs take over.
		command(level, quietSource(level), "item replace entity " + mob.getUUID() + " weapon.mainhand with minecraft:air");

		level.playSound(null, mob.blockPosition(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 2.0F, 0.7F);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 1.0, mob.getZ(), 40, 0.6, 0.8, 0.6, 0.05);
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
			int enchantLevel = 1 + random.nextInt(BOOK_ENCHANT_MAX[pick]);
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
