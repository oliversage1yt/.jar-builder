package com.skyislands;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.skyislands.world.CloudDeck;

/**
 * The Wind Spears: a spear for every tier of the vanilla spears (wooden, stone, copper, iron, golden, diamond, netherite)
 * that can also be thrown.
 *
 * <p>Each Wind Spear is its own item that starts as a copy of the matching vanilla spear (every data component: damage,
 * durability, jab attack, the Lunge enchantment, repair material, ...), so poking with it behaves exactly like poking with
 * the vanilla spear. What this class changes is the right click: instead of charging, it throws the spear (or, with
 * Riptide, launches the player). The flight is worked out here (the picture is {@link SpearVisual}), so no new entity type
 * is needed.
 *
 * <p>Enchantments: Mending, Unbreaking, Sharpness, Looting and Lunge are the vanilla ones, allowed on Wind Spears by the item
 * tags in data/minecraft/tags/item/enchantable. Loyalty, Riptide and Piercing are the vanilla ones, allowed on Wind Spears by adding them to the trident and crossbow
 * enchantable item tags (data/minecraft/tags/item/enchantable).
 */
public final class WindSpears {
	static final String[] TIERS = {"wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"};

	private static final String LOYALTY = "minecraft:loyalty";
	private static final String RIPTIDE = "minecraft:riptide";
	private static final String PIERCING = "minecraft:piercing";
	private static final String QUICK_CHARGE = "minecraft:quick_charge";
	private static final String WIND_BURST = "minecraft:wind_burst";
	private static final String LUNGE = "minecraft:lunge";
	/** Hotbar cooldowns are shared by every stack of an item unless the stack names its own cooldown group: riptide spears get this one. */
	private static final Identifier RIPTIDE_COOLDOWN_GROUP = SkyIslands.id("riptide_windspear");
	/** The block's cooldown group, used by the Wind Spears without Riptide (so it never locks a Riptide spear, and the other way round). */
	private static final Identifier BLOCK_COOLDOWN_GROUP = SkyIslands.id("block_windspear");
	/** After an attack breaks the block, it cannot be used for this long: twice a vanilla shield's 100 tick disable. */
	private static final int BLOCK_COOLDOWN_TICKS = 200;
	/** The client's "attack + use held" message counts as fresh for this many ticks. */
	private static final int BLOCK_FRESH_TICKS = 4;
	/** Letting go of the use button this soon after blocking does not throw the spear. */
	private static final int BLOCK_NO_THROW_TICKS = 10;

	private static final double THROW_SPEED = 2.5;
	private static final double GRAVITY = 0.03;
	private static final int MAX_AGE = 200;
	/** Creative players can throw their spear again after this many ticks. */
	private static final int THROW_COOLDOWN_TICKS = 10;
	/** Riptide can be used again after this many ticks (3 seconds). */
	private static final int RIPTIDE_COOLDOWN_TICKS = 60;
	/** A spear must be charged this long (ticks) before letting go throws it, like a trident. */
	private static final int MIN_CHARGE_TICKS = 10;
	/** Each level of Quick Charge takes this many ticks off the charge (10 -> 7 -> 4 -> 2 at levels 1, 2 and 3). */
	private static final int QUICK_CHARGE_TICKS_PER_LEVEL = 3;
	/** The shortest a charge can get, however high Quick Charge is. */
	private static final int MIN_QUICK_CHARGE_TICKS = 2;

	/**
	 * Upward speed (blocks per tick) a Wind Burst launch gives, by enchantment level 1, 2, 3. With normal drag and gravity this
	 * carries an entity about 7, 12 and 18 blocks up. Levels above 3 (commands, datapacks) use the level 3 value.
	 */
	private static final double[] BURST_LIFT = {1.1, 1.5, 1.9};
	/** How much of the way the holder looks (flat) a launch also pushes, so it is mostly straight up. */
	private static final double BURST_FORWARD = 0.15;
	/** How far a blocking Wind Spear with Wind Burst reaches for something to launch (blocks). */
	private static final double BURST_REACH = 5.0;
	/** After a Wind Burst launch (either kind) that same player must wait this many ticks (2 seconds). */
	private static final int BURST_COOLDOWN_TICKS = 40;
	/** A Riptide + Wind Burst spear launches its holder this many times before they have to land (a double jump). */
	private static final int MAX_POKE_BURSTS = 2;
	/** The shortest wait between two poke launches (half a second): a double click cannot launch twice. */
	private static final int POKE_BURST_COOLDOWN_TICKS = 10;
	/**
	 * A Riptide + Wind Burst spear that also has Lunge keeps its two launches apart by a Lunge: after a launch, the next press of
	 * attack is the Lunge jab and launches nothing, and only the press after that can launch again (Wind Burst, Lunge, Wind
	 * Burst). A launch that is not followed by a Lunge press within this many ticks (2 seconds) stops waiting for one.
	 */
	private static final int LUNGE_WAIT_TICKS = 40;
	/** After the Lunge press, the next press may launch only after this many ticks, so one stroke cannot do both. */
	private static final int POKE_AFTER_LUNGE_TICKS = 6;
	/**
	 * Soft landing: a Riptide + Wind Burst poke that comes this many ticks (half a second) or less before the ground makes a
	 * burst, and the fall damage of that landing is cut by SOFT_LANDING_REDUCTION.
	 */
	private static final int SOFT_LANDING_WINDOW_TICKS = 10;
	/** The cut in fall damage (90%). */
	private static final float SOFT_LANDING_REDUCTION = 0.9F;
	/** How long a soft landing is good for (the landing must come within this many ticks of the poke). */
	private static final int SOFT_LANDING_VALID_TICKS = 20;
	/** A fall shorter than this many blocks does no damage, so a poke after it saves nothing (it launches as usual). */
	private static final double SOFT_LANDING_MIN_FALL = 3.0;
	/** How far down to look for the ground (blocks). */
	private static final int SOFT_LANDING_SCAN_BLOCKS = 40;

	/**
	 * Fall parry: a Wind Spear with Wind Burst but without Riptide that STARTS blocking this many ticks (1 second) or less before
	 * a landing cuts that landing's fall damage by FALL_PARRY_REDUCTION. Holding the block through the whole fall does not
	 * count: the block has to be (re)started inside this window.
	 */
	private static final int FALL_PARRY_WINDOW_TICKS = 20;
	/** The cut in fall damage of a parried landing (75%). */
	private static final float FALL_PARRY_REDUCTION = 0.75F;
	/** An entity thrown up by a blocking Wind Burst spear takes this much less fall damage on the landing (50%). */
	private static final float LAUNCHED_FALL_REDUCTION = 0.5F;
	/** A launched entity that has still not come down after this many ticks (30 seconds) loses its reduced landing. */
	private static final int LAUNCH_TIMEOUT_TICKS = 600;
	/** A thrown spear with Loyalty that gets farther than this many blocks from its owner comes back at once. */
	private static final double LOYALTY_RECALL_BLOCKS = 60.0;

	/** Arrows keep 60% of their speed per tick in water; a Wind Spear does the same. */
	private static final double WATER_DRAG = 0.6;
	/** Lava is thicker still. */
	private static final double LAVA_DRAG = 0.4;
	private static final int USE_DURATION = 72000;
	/** A stuck spear can be picked up / starts to fly home after this many ticks. */
	private static final int STUCK_DELAY_TICKS = 5;
	/** A creative player's stuck spear (only a copy) disappears after this many ticks. */
	private static final int CREATIVE_STUCK_TICKS = 1200;
	/** A spear thrown by a mob (a copy) disappears this many ticks after it sticks. */
	private static final int MOB_STUCK_TICKS = 100;
	private static final double MOB_THROW_SPEED = 1.8;
	/** After hitting an entity a spear keeps this part of its speed, turned round (a trident keeps -0.01 sideways and -0.1 up and down). */
	private static final double BOUNCE_SIDEWAYS = -0.1;
	private static final double BOUNCE_VERTICAL = -0.1;
	/** A spear that has bounced off falls faster than one in flight. */
	private static final double SPENT_GRAVITY = 0.06;

	private static final Set<String> REGISTERED = new HashSet<>();
	/** Each wind spear item -> the vanilla spear it starts as a copy of. */
	private static final Map<Item, Item> VANILLA_OF = new HashMap<>();
	private static final List<Thrown> THROWN = new ArrayList<>();
	private static final Map<UUID, Integer> NEXT_THROW = new HashMap<>();
	private static final Map<UUID, Integer> NEXT_RIPTIDE = new HashMap<>();
	/** The next tick a player may Wind Burst again, shared by the poke launch and the block launch. */
	private static final Map<UUID, Integer> NEXT_BURST = new HashMap<>();
	/** How many Riptide + Wind Burst poke launches a player has used since they last landed. */
	private static final Map<UUID, Integer> BURST_USES = new HashMap<>();
	/** The tick of a player's last poke launch (so the launch tick itself, still on the ground, does not count as a landing). */
	private static final Map<UUID, Integer> BURST_LAST = new HashMap<>();
	/** The next tick a player's poke may launch again: until then attack only jabs (so a Lunge can come between the two launches of a double jump). */
	private static final Map<UUID, Integer> NEXT_POKE = new HashMap<>();
	/** Players whose last poke launch is waiting for a Lunge press (value: the tick of that launch). */
	private static final Map<UUID, Integer> LUNGE_WAIT = new HashMap<>();
	/** Players who poked just before landing: the last tick their fall damage is still cut. */
	private static final Map<UUID, Integer> SOFT_LANDING_UNTIL = new HashMap<>();
	/** Each player's height a tick ago, how fast they moved up (negative: down) last tick, and how far they have fallen so far. */
	private static final Map<UUID, Double> LAST_Y = new HashMap<>();
	private static final Map<UUID, Double> SPEED_Y = new HashMap<>();
	private static final Map<UUID, Double> FALLEN = new HashMap<>();
	/** Players who started a block in the air: the last tick their landing is parried (see FALL_PARRY_WINDOW_TICKS). */
	private static final Map<UUID, Integer> FALL_PARRY_UNTIL = new HashMap<>();
	/** Entities a blocking Wind Burst spear threw up and that have not landed yet (value: the tick of the launch). */
	private static final Map<UUID, Integer> LAUNCHED = new HashMap<>();
	/** Players who were blocking last tick, to see the moment a block starts. */
	private static final Set<UUID> WAS_BLOCKING = new HashSet<>();
	/** Players currently holding the block (value: the tick of the client's last message). */
	private static final Map<UUID, Integer> BLOCK_HELD = new HashMap<>();
	/** The last tick a player held the block, to stop letting go from throwing the spear. */
	private static final Map<UUID, Integer> BLOCK_LAST = new HashMap<>();
	private static final Map<UUID, Integer> NEXT_BLOCK = new HashMap<>();
	private static final List<PendingCooldown> PENDING = new ArrayList<>();

	private WindSpears() { }

	static boolean registered(String tier) {
		return REGISTERED.contains(tier);
	}

	public static void register() {
		for (String tier : TIERS) {
			Identifier vanillaId = Identifier.fromNamespaceAndPath("minecraft", tier + "_spear");

			if (!BuiltInRegistries.ITEM.containsKey(vanillaId)) {
				SkyIslands.LOGGER.warn("Sky Islands: there is no {} in this version, so there is no {} wind spear", vanillaId, tier);
				continue;
			}

			Identifier id = SkyIslands.id(tier + "_windspear");
			// Do NOT read the vanilla spear's components here: item components are not bound yet while mods initialize
			// ("Components not bound yet"). They are copied in copyVanillaComponents(), once the game has bound them.
			Item.Properties properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
			properties.component(DataComponents.ITEM_MODEL, id);
			properties.component(DataComponents.ITEM_NAME, Component.translatable("item.sky_islands." + tier + "_windspear"));
			Item windSpear = Registry.register(BuiltInRegistries.ITEM, id, new WindSpearItem(properties));
			VANILLA_OF.put(windSpear, BuiltInRegistries.ITEM.getValue(vanillaId));
			REGISTERED.add(tier);
		}

		DefaultItemComponentEvents.MODIFY.register(context ->
			context.modify(VANILLA_OF::containsKey, (builder, item) -> copyVanillaComponents(builder, VANILLA_OF.get(item), item)));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> dropAll());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int now = server.getTickCount();
			tick(now);
			blockTick(server, now);
		});

		// the Wind Spears sit in the combat tab right after the vanilla spears
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> {
			List<Item> windSpears = new ArrayList<>();

			for (String tier : TIERS) {
				Identifier windId = SkyIslands.id(tier + "_windspear");

				if (BuiltInRegistries.ITEM.containsKey(windId)) {
					windSpears.add(BuiltInRegistries.ITEM.getValue(windId));
				}
			}

			if (!windSpears.isEmpty()) {
				output.insertAfter(Items.NETHERITE_SPEAR, windSpears.toArray(new Item[0]));
			}
		});

		PayloadTypeRegistry.serverboundPlay().register(WindBlockPayload.TYPE, WindBlockPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(WindBlockPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();

			if (payload.holding()) {
				int now = context.server().getTickCount();
				BLOCK_HELD.put(player.getUUID(), now);
				BLOCK_LAST.put(player.getUUID(), now);
			} else {
				BLOCK_HELD.remove(player.getUUID());
			}
		});
		PayloadTypeRegistry.serverboundPlay().register(WindPokePayload.TYPE, WindPokePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(WindPokePayload.TYPE, (payload, context) -> {
			pokeBurst(context.player(), context.server().getTickCount());
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !blocks(entity, source));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !softLanding(entity, source, amount));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !fallReduction(entity, source, amount));
	}

	public static void clear() {
		THROWN.clear();
		NEXT_THROW.clear();
		NEXT_RIPTIDE.clear();
		NEXT_BURST.clear();
		BURST_USES.clear();
		BURST_LAST.clear();
		NEXT_POKE.clear();
		LUNGE_WAIT.clear();
		SOFT_LANDING_UNTIL.clear();
		FALL_PARRY_UNTIL.clear();
		LAUNCHED.clear();
		LAST_Y.clear();
		SPEED_Y.clear();
		FALLEN.clear();
		WAS_BLOCKING.clear();
		BLOCK_HELD.clear();
		BLOCK_LAST.clear();
		NEXT_BLOCK.clear();
		PENDING.clear();
	}

	/** Copies every default component of the vanilla spear onto the wind spear (runs when the game binds item components). */
	private static void copyVanillaComponents(DataComponentMap.Builder builder, Item vanilla, Item windSpear) {
		for (TypedDataComponent<?> component : vanilla.components()) {
			// The vanilla spear's right-click charge-and-lunge is replaced by this mod's charge-and-throw.
			Identifier type = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());

			if (type != null && type.getPath().equals("kinetic_weapon")) {
				continue;
			}

			copy(builder, component);
		}

		Identifier id = BuiltInRegistries.ITEM.getKey(windSpear);
		String tier = id.getPath().substring(0, id.getPath().length() - "_windspear".length());
		builder.set(DataComponents.ITEM_MODEL, id);
		builder.set(DataComponents.ITEM_NAME, Component.translatable("item.sky_islands." + tier + "_windspear"));
	}

	private static <T> void copy(DataComponentMap.Builder builder, TypedDataComponent<T> component) {
		builder.set(component.type(), component.value());
	}

	// ------------------------------------------------------------------ the stack

	private static boolean isWindSpear(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return key.getNamespace().equals(SkyIslands.MOD_ID) && key.getPath().endsWith("_windspear");
	}

	private static String tierOf(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		return path.substring(0, path.length() - "_windspear".length());
	}

	/** The level of an enchantment on a stack, by its id such as "minecraft:sharpness" (0 if it is not there). */
	private static int levelOf(ItemStack stack, String enchantment) {
		ItemEnchantments enchantments = stack.getEnchantments();

		for (Holder<Enchantment> holder : enchantments.keySet()) {
			if (holder.getRegisteredName().equals(enchantment)) {
				return enchantments.getLevel(holder);
			}
		}

		return 0;
	}

	/** The damage the spear does in a fight: its attack damage attribute plus the base 1 (a table if that cannot be read). */
	private static float baseDamage(ItemStack stack) {
		try {
			ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
			double total = 0.0;

			for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
				if (entry.attribute().equals(Attributes.ATTACK_DAMAGE)) {
					total += entry.modifier().amount();
				}
			}

			if (total > 0.0) {
				return (float) (1.0 + total);
			}
		} catch (RuntimeException ignored) {
			// fall through to the table
		}

		return switch (tierOf(stack)) {
			case "wooden" -> 3.0F;
			case "stone" -> 4.0F;
			case "copper" -> 4.5F;
			case "iron" -> 5.0F;
			case "golden" -> 3.0F;
			case "diamond" -> 6.0F;
			default -> 7.0F;
		};
	}

	/** Uses up one point of durability (Unbreaking can save it). Returns true when that broke the spear. */
	private static boolean wear(ItemStack stack, Player player) {
		int unbreaking = levelOf(stack, "minecraft:unbreaking");

		if (unbreaking > 0 && player.getRandom().nextInt(unbreaking + 1) != 0) {
			return false;
		}

		stack.setDamageValue(stack.getDamageValue() + 1);
		return stack.getDamageValue() >= stack.getMaxDamage();
	}

	// ------------------------------------------------------------------ right click

	/** The item: right click starts charging (the trident arm pose), letting go throws the spear (or, with Riptide, launches the player). */
	static final class WindSpearItem extends Item {
		WindSpearItem(Item.Properties properties) {
			super(properties);
		}

		@Override
		public InteractionResult use(Level level, Player player, InteractionHand hand) {
			ItemStack stack = player.getItemInHand(hand);

			// The last point of durability cannot be spent: the spear would break in the player's hand.
			if (!player.isCreative() && stack.getMaxDamage() > 0 && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
				return InteractionResult.FAIL;
			}

			if (levelOf(stack, RIPTIDE) > 0 && windBlocked(player)) {
				if (level instanceof ServerLevel serverLevel) {
					actionBar(serverLevel, player, "The wind cannot carry you in water, lava or rain");
				}

				return InteractionResult.FAIL;
			}

			useCooldownGroup(stack, levelOf(stack, RIPTIDE) > 0 ? RIPTIDE_COOLDOWN_GROUP : BLOCK_COOLDOWN_GROUP);
			player.startUsingItem(hand);
			return InteractionResult.CONSUME;
		}

		@Override
		public int getUseDuration(ItemStack stack, LivingEntity user) {
			return USE_DURATION;
		}

		@Override
		public ItemUseAnimation getUseAnimation(ItemStack stack) {
			// while blocking: the shield's guarding pose instead of the throwing pose (the bow pose zoomed the screen
			// and stretched the spear in first person)
			return hasBlockFlag(stack) ? ItemUseAnimation.BLOCK : ItemUseAnimation.TRIDENT;
		}

		@Override
		public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int timeLeft) {
			if (!(level instanceof ServerLevel serverLevel) || !(user instanceof ServerPlayer player)) {
				return false;
			}

			return release(serverLevel, player, stack, USE_DURATION - timeLeft);
		}
	}

	/** Called when a player lets go of a charging spear; {@code charged} is how many ticks it was charged. Returns true if it was used. */
	private static boolean release(ServerLevel level, ServerPlayer player, ItemStack stack, int charged) {
		if (player.isSpectator() || !isWindSpear(stack) || charged < chargeTicks(stack)) {
			return false;
		}

		int now = level.getServer().getTickCount();

		if (levelOf(stack, RIPTIDE) > 0) {
			return riptide(level, player, stack, now) == InteractionResult.SUCCESS;
		}

		// letting go right after blocking only lowers the guard, it does not throw the spear
		Integer lastBlock = BLOCK_LAST.get(player.getUUID());

		if (lastBlock != null && now - lastBlock <= BLOCK_NO_THROW_TICKS) {
			return false;
		}

		return throwSpear(level, player, stack) == InteractionResult.SUCCESS;
	}

	/**
	 * How many ticks the spear has to be charged before letting go does something. Quick Charge shortens it. A spear with
	 * Riptide ignores Quick Charge (the two cannot be put together, this only covers one that got both some other way).
	 */
	private static int chargeTicks(ItemStack stack) {
		int quick = levelOf(stack, QUICK_CHARGE);

		if (quick <= 0 || levelOf(stack, RIPTIDE) > 0) {
			return MIN_CHARGE_TICKS;
		}

		return Math.max(MIN_QUICK_CHARGE_TICKS, MIN_CHARGE_TICKS - QUICK_CHARGE_TICKS_PER_LEVEL * quick);
	}

	/** Yaw and pitch (degrees) to the direction a player looks in: {x, y, z}. */
	private static double[] lookOf(Player player) {
		double yaw = Math.toRadians(player.getYRot());
		double pitch = Math.toRadians(player.getXRot());
		return new double[] {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
	}

	private static InteractionResult throwSpear(ServerLevel level, ServerPlayer player, ItemStack stack) {
		ItemStack thrown = stack.copy();
		thrown.setCount(1);

		if (!player.isCreative()) {
			wear(thrown, player);
			stack.shrink(1);
		}

		double[] look = lookOf(player);
		double x = player.getX() + look[0] * 0.8;
		double y = player.getEyeY() - 0.15 + look[1] * 0.8;
		double z = player.getZ() + look[2] * 0.8;

		Thrown spear = new Thrown(level, player.getUUID(), thrown, player.isCreative(), x, y, z,
				look[0] * THROW_SPEED, look[1] * THROW_SPEED, look[2] * THROW_SPEED);
		spear.id = SpearVisual.spawn(level, spear.tier, x, y, z, look[0], look[1], look[2]);
		THROWN.add(spear);

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:entity.breeze.idle_air player @a %.1f %.1f %.1f 1.0 1.0", player.getX(), player.getY(), player.getZ()));
		return InteractionResult.SUCCESS;
	}

	/** A mob (a rain drowned) throws a copper Wind Spear at a target. The spear is only a copy and only hurts players. */
	static void throwFromMob(ServerLevel level, Mob mob, LivingEntity target) {
		Item item = BuiltInRegistries.ITEM.getValue(SkyIslands.id("copper_windspear"));
		ItemStack stack = new ItemStack(item);

		double sx = mob.getX();
		double sy = mob.getEyeY() - 0.2;
		double sz = mob.getZ();
		double tx = target.getX() - sx;
		double tz = target.getZ() - sz;
		double ty = target.getEyeY() - 0.35 - sy;
		double flat = Math.sqrt(tx * tx + tz * tz);

		// aim a little high to make up for the fall on the way
		double flightTicks = flat / MOB_THROW_SPEED;
		ty += GRAVITY * flightTicks * (flightTicks + 1.0) / 2.0;

		double length = Math.sqrt(flat * flat + ty * ty);

		if (length < 1.0E-6) {
			return;
		}

		double ux = tx / length + level.getRandom().nextGaussian() * 0.02;
		double uy = ty / length + level.getRandom().nextGaussian() * 0.02;
		double uz = tz / length + level.getRandom().nextGaussian() * 0.02;
		double norm = Math.sqrt(ux * ux + uy * uy + uz * uz);
		ux /= norm;
		uy /= norm;
		uz /= norm;

		double x = sx + ux * 0.8;
		double y = sy + uy * 0.8;
		double z = sz + uz * 0.8;

		Thrown spear = new Thrown(level, mob.getUUID(), stack, true, x, y, z,
				ux * MOB_THROW_SPEED, uy * MOB_THROW_SPEED, uz * MOB_THROW_SPEED);
		spear.mob = true;
		spear.id = SpearVisual.spawn(level, spear.tier, x, y, z, ux, uy, uz);
		THROWN.add(spear);

		swingArm(mob);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:entity.breeze.idle_air hostile @a %.1f %.1f %.1f 1.0 1.0", mob.getX(), mob.getY(), mob.getZ()));
	}

	/** True when the wind cannot carry the player: the opposite of a trident, a Wind Spear's Riptide fails in water, lava and rain. */
	private static boolean windBlocked(Player player) {
		return player.isInWater() || player.isInLava() || inRain(player);
	}

	/** Standing in the rain (not above the clouds, where no rain is shown). */
	private static boolean inRain(Player player) {
		Level level = player.level();

		if (!level.isRaining()) {
			return false;
		}

		double cloudY = CloudDeck.heightAt(Tuning.CLOUD_BASE_Y, Tuning.CLOUD_VARIATION, player.getX(), player.getZ());

		if (player.getEyeY() > cloudY + Tuning.CLOUD_HALF_THICKNESS) {
			return false;
		}

		BlockPos pos = player.blockPosition();
		return level.isRainingAt(pos) || level.isRainingAt(pos.above());
	}

	/**
	 * Gives a spear a cooldown group of its own. The hotbar cooldown is otherwise kept per item, so cooling down one copper
	 * Wind Spear also locked every other copper Wind Spear. A Riptide spear gets the riptide group and any other spear the
	 * block group, so each only shares its cooldown with spears of its own kind. The component's own 'seconds' is a token
	 * 1 tick: the real cooldown is applied by {@link #showCooldown}.
	 */
	private static void useCooldownGroup(ItemStack stack, Identifier group) {
		try {
			UseCooldown current = stack.get(DataComponents.USE_COOLDOWN);

			if (current != null && group.equals(current.cooldownGroup().orElse(null))) {
				return;
			}

			stack.set(DataComponents.USE_COOLDOWN, new UseCooldown(0.05F, Optional.of(group)));
		} catch (RuntimeException e) {
			SkyIslands.LOGGER.warn("Sky Islands: could not give the Wind Spear its own cooldown group: {}", e.toString());
		}
	}

	private static final class PendingCooldown {
		final ServerPlayer player;
		final ItemStack stack;
		final int end;
		int at;
		int left = 3;

		PendingCooldown(ServerPlayer player, ItemStack stack, int end, int at) {
			this.player = player;
			this.stack = stack;
			this.end = end;
			this.at = at;
		}
	}

	/** Applies the cooldown again a few ticks later, in case something else on the use/release path reset it. */
	private static void scheduleCooldown(ServerPlayer player, ItemStack stack, int ticks, int now) {
		PENDING.add(new PendingCooldown(player, stack, now + ticks, now + 1));
	}

	// ------------------------------------------------------------------ the block

	/** True while the player holds attack and use together with a ready Wind Spear (without Riptide) in use. */
	private static boolean isBlocking(ServerPlayer player, int now) {
		Integer held = BLOCK_HELD.get(player.getUUID());

		if (held == null || now - held > BLOCK_FRESH_TICKS || !player.isUsingItem() || NEXT_BLOCK.getOrDefault(player.getUUID(), 0) > now) {
			return false;
		}

		ItemStack stack = player.getUseItem();
		return isWindSpear(stack) && levelOf(stack, RIPTIDE) <= 0;
	}

	/** The spear in a blocking player's hand carries a model flag, so it is shown (and posed) as a block. */
	private static boolean hasBlockFlag(ItemStack stack) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return data != null && !data.flags().isEmpty() && Boolean.TRUE.equals(data.flags().get(0));
	}

	private static void setBlockFlag(ItemStack stack, boolean on) {
		try {
			if (on) {
				stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(Boolean.TRUE), List.of(), List.of()));
			} else {
				stack.remove(DataComponents.CUSTOM_MODEL_DATA);
			}
		} catch (RuntimeException e) {
			SkyIslands.LOGGER.warn("Sky Islands: could not change the Wind Spear block flag: {}", e.toString());
		}
	}

	/** The block flag on the spear in use (cleared again when the block ends). The slowness is done on the client, see WindBlockInput. */
	private static void blockTick(MinecraftServer server, int now) {
		trackLaunched(server, now);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean blocking = isBlocking(player, now);

			// a spent poke burst comes back once the player is down again (a few ticks pass first: the launch tick is still on the ground)
			Integer last = BURST_LAST.get(player.getUUID());

			if (last != null && now - last > 5 && landed(player)) {
				BURST_USES.remove(player.getUUID());
				BURST_LAST.remove(player.getUUID());
				LUNGE_WAIT.remove(player.getUUID());
			}

			trackFall(player);
			Integer cushion = SOFT_LANDING_UNTIL.get(player.getUUID());

			if (cushion != null && cushion < now) {
				SOFT_LANDING_UNTIL.remove(player.getUUID());
			}

			// a parry only lives until the player is down (the landing's damage has been dealt by now) or its window is over
			Integer parry = FALL_PARRY_UNTIL.get(player.getUUID());

			if (parry != null && (parry < now || landed(player))) {
				FALL_PARRY_UNTIL.remove(player.getUUID());
			}

			if (blocking) {
				// every tick of a block, a Wind Burst spear launches whatever the player is looking at (the cooldown stops repeats)
				if (WAS_BLOCKING.add(player.getUUID())) {
					startFallParry(player, now);
				}

				blockBurst(player, now);
			} else {
				WAS_BLOCKING.remove(player.getUUID());
			}

			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = player.getItemInHand(hand);

				if (isWindSpear(stack) && hasBlockFlag(stack) != (blocking && hand == player.getUsedItemHand())) {
					setBlockFlag(stack, blocking && hand == player.getUsedItemHand());
				}
			}

			// a spear that was swapped out mid-block must not keep the flag
			if (now % 20 == 0) {
				for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
					ItemStack stack = player.getInventory().getItem(slot);

					if (isWindSpear(stack) && hasBlockFlag(stack) && stack != player.getUseItem()) {
						setBlockFlag(stack, false);
					}
				}
			}
		}
	}

	/**
	 * A Wind Spear without Riptide blocks while the use and attack buttons are held together, like a shield. Any attack from
	 * the front is stopped and also breaks the block: it then has a cooldown twice as long as a vanilla shield's.
	 * Returns true when this hit was blocked.
	 */
	private static boolean blocks(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
			return false;
		}

		int now = level.getServer().getTickCount();

		if (!isBlocking(player, now) || source.is(DamageTypeTags.BYPASSES_SHIELD)) {
			return false;
		}

		ItemStack stack = player.getUseItem();

		Vec3 from = source.getSourcePosition();

		if (from == null) {
			return false;
		}

		// only from the front, like a shield
		Vec3 toPlayer = player.position().subtract(from);
		Vec3 flat = new Vec3(toPlayer.x, 0.0, toPlayer.z).normalize();

		if (flat.dot(player.getViewVector(1.0F)) >= 0.0) {
			return false;
		}

		useCooldownGroup(stack, BLOCK_COOLDOWN_GROUP);
		NEXT_BLOCK.put(player.getUUID(), now + BLOCK_COOLDOWN_TICKS);
		BLOCK_HELD.remove(player.getUUID());
		showCooldown(player, stack, BLOCK_COOLDOWN_TICKS);
		scheduleCooldown(player, stack, BLOCK_COOLDOWN_TICKS, now);
		player.stopUsingItem();

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:item.shield.block player @a %.1f %.1f %.1f 1.0 1.0", player.getX(), player.getY(), player.getZ()));
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:item.shield.break player @a %.1f %.1f %.1f 0.8 1.0", player.getX(), player.getY(), player.getZ()));
		return true;
	}

	/** Puts the spear on the hotbar cooldown (the grey sweep over the slot, like an ender pearl). Looked up by name, as the method changed between versions. */
	private static void showCooldown(ServerPlayer player, ItemStack stack, int ticks) {
		// make sure the client has the spear's cooldown group before it hears about the cooldown
		player.containerMenu.broadcastChanges();

		Object cooldowns = player.getCooldowns();

		for (Method method : cooldowns.getClass().getMethods()) {
			if (!method.getName().equals("addCooldown") || method.getParameterCount() != 2 || method.getParameterTypes()[1] != int.class) {
				continue;
			}

			Class<?> first = method.getParameterTypes()[0];

			try {
				if (first.isInstance(stack)) {
					method.invoke(cooldowns, stack, ticks);
					return;
				}

				if (first.isInstance(stack.getItem())) {
					method.invoke(cooldowns, stack.getItem(), ticks);
					return;
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				SkyIslands.LOGGER.warn("Sky Islands: could not show the Wind Spear cooldown: {}", e.toString());
				return;
			}
		}

		SkyIslands.LOGGER.warn("Sky Islands: no cooldown method found, the Wind Spear cooldown will not show on the hotbar");
	}

	/** Makes a mob swing its main arm. */
	static void swingArm(Mob mob) {
		// Minecraft 26.3 has swing(hand, swingAnimation, boolean); older versions had swing(hand) and swing(hand, boolean).
		// The method is looked up by its parameters so that either shape works.
		for (Method method : mob.getClass().getMethods()) {
			Class<?>[] types = method.getParameterTypes();

			if (!method.getName().equals("swing") || types.length == 0 || types[0] != InteractionHand.class) {
				continue;
			}

			Object[] args = new Object[types.length];
			args[0] = InteractionHand.MAIN_HAND;
			boolean usable = true;

			for (int i = 1; i < types.length; i++) {
				if (types[i] == boolean.class) {
					args[i] = Boolean.TRUE;
				} else if (types[i] == SwingAnimation.class) {
					args[i] = swingAnimationOf(mob.getMainHandItem());
					usable = args[i] != null;
				} else {
					usable = false;
				}
			}

			if (!usable) {
				continue;
			}

			try {
				method.invoke(mob, args);
				return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				SkyIslands.LOGGER.warn("Sky Islands: could not make a mob swing its arm: {}", e.toString());
				return;
			}
		}

		if (!SWING_WARNED) {
			SWING_WARNED = true;
			SkyIslands.LOGGER.warn("Sky Islands: no usable swing method found, mobs will not show a swing");
		}
	}

	private static boolean SWING_WARNED;

	/** The held item's swing animation (its component, or the default one when it has none). Looked up by name. */
	private static Object swingAnimationOf(ItemStack stack) {
		try {
			Object type = DataComponents.class.getField("SWING_ANIMATION").get(null);

			if (type instanceof DataComponentType<?> componentType) {
				Object value = stack.get(componentType);

				if (value != null) {
					return value;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through to the default
		}

		try {
			return SwingAnimation.class.getField("DEFAULT").get(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** A short message above the hotbar. */
	private static void actionBar(ServerLevel level, Player player, String text) {
		ArenaBoss.command(level, ArenaBoss.quietSource(level), "title " + player.getUUID() + " actionbar {\"text\":\"" + text + "\"}");
	}

	private static InteractionResult riptide(ServerLevel level, ServerPlayer player, ItemStack stack, int now) {
		if (windBlocked(player)) {
			actionBar(level, player, "The wind cannot carry you in water, lava or rain");
			return InteractionResult.FAIL;
		}

		if (NEXT_RIPTIDE.getOrDefault(player.getUUID(), 0) > now) {
			actionBar(level, player, "The Wind Spear is still recharging");
			return InteractionResult.FAIL;
		}

		if (!player.isCreative() && stack.getMaxDamage() > 0 && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
			return InteractionResult.FAIL;
		}

		int enchant = levelOf(stack, RIPTIDE);
		useCooldownGroup(stack, RIPTIDE_COOLDOWN_GROUP);
		NEXT_RIPTIDE.put(player.getUUID(), now + RIPTIDE_COOLDOWN_TICKS);
		showCooldown(player, stack, RIPTIDE_COOLDOWN_TICKS);
		scheduleCooldown(player, stack, RIPTIDE_COOLDOWN_TICKS, now);

		// The launch: the new velocity is sent straight to the player's client (a server-side push alone would be overwritten by it).
		double speed = 0.8 + 0.5 * enchant;
		double[] look = lookOf(player);
		Vec3 velocity = new Vec3(look[0] * speed, Math.max(look[1] * speed, -0.3) + 0.45, look[2] * speed);
		player.setDeltaMovement(velocity);
		player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));

		level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.2, player.getZ(), 16, 0.4, 0.1, 0.4, 0.05);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:item.trident.riptide_%d player @a %.1f %.1f %.1f 1.0 1.0", Math.min(3, enchant), player.getX(), player.getY(), player.getZ()));

		if (!player.isCreative() && wear(stack, player)) {
			stack.shrink(1);
		}

		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ wind burst

	/** True when the player counts as landed: on the ground, in water, riding something, or touching down on a slime block (which bounces them). */
	private static boolean landed(ServerPlayer player) {
		if (player.onGround() || player.isInWater() || player.isPassenger()) {
			return true;
		}

		// a slime block throws the player back up, so they may never be "on the ground" when the server looks
		BlockPos below = new BlockPos((int) Math.floor(player.getX()), (int) Math.floor(player.getY() - 0.2), (int) Math.floor(player.getZ()));
		return player.level().hasChunkAt(below) && player.level().getBlockState(below).is(Blocks.SLIME_BLOCK);
	}

	/** The upward speed for a Wind Burst level. */
	private static double burstLift(int level) {
		return BURST_LIFT[Math.min(Math.max(level, 1), BURST_LIFT.length) - 1];
	}

	/** Sets an entity's velocity and tells the players who can see it (a player's own client needs it to accept the launch). */
	private static void launch(ServerLevel level, Entity entity, Vec3 velocity) {
		entity.setDeltaMovement(velocity);
		ClientboundSetEntityMotionPacket packet = new ClientboundSetEntityMotionPacket(entity.getId(), velocity);

		for (ServerPlayer viewer : level.players()) {
			if (viewer == entity || viewer.distanceToSqr(entity) < 128.0 * 128.0) {
				viewer.connection.send(packet);
			}
		}
	}

	/**
	 * A spear with Riptide and Wind Burst: pressing attack (a poke, whether or not it hits anything) throws the holder
	 * straight up, higher with each level of Wind Burst, like a wind charge.
	 */
	private static void pokeBurst(ServerPlayer player, int now) {
		ItemStack stack = player.getMainHandItem();

		if (player.isSpectator() || !(player.level() instanceof ServerLevel level) || !isWindSpear(stack)) {
			return;
		}

		int burst = levelOf(stack, WIND_BURST);

		if (burst <= 0 || levelOf(stack, RIPTIDE) <= 0) {
			return;
		}

		if (!player.isCreative() && stack.getMaxDamage() > 0 && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
			return;
		}

		// a poke just before the ground is a soft landing, not a launch
		if (cushionLanding(level, player, stack, burst, now)) {
			return;
		}

		UUID id = player.getUUID();

		// with Lunge, the press after a launch is the Lunge jab (the game's own business), not another launch
		Integer waiting = LUNGE_WAIT.remove(id);

		if (waiting != null && levelOf(stack, LUNGE) > 0 && now - waiting < LUNGE_WAIT_TICKS) {
			NEXT_POKE.put(id, now + POKE_AFTER_LUNGE_TICKS);
			return;
		}

		if (NEXT_POKE.getOrDefault(id, 0) > now) {
			return;
		}

		// two launches per flight (a double jump): after that the burst is back once the player has landed
		if (BURST_USES.getOrDefault(id, 0) >= MAX_POKE_BURSTS) {
			return;
		}

		if (windBlocked(player)) {
			actionBar(level, player, "The wind cannot carry you in water, lava or rain");
			return;
		}

		NEXT_POKE.put(id, now + POKE_BURST_COOLDOWN_TICKS);
		BURST_USES.merge(id, 1, Integer::sum);
		BURST_LAST.put(id, now);

		// a launch that has another one to come waits for a Lunge in between
		if (levelOf(stack, LUNGE) > 0 && BURST_USES.get(id) < MAX_POKE_BURSTS) {
			LUNGE_WAIT.put(id, now);
		}

		double[] look = lookOf(player);
		double flat = Math.sqrt(look[0] * look[0] + look[2] * look[2]);
		double fx = flat > 1.0E-6 ? look[0] / flat * BURST_FORWARD : 0.0;
		double fz = flat > 1.0E-6 ? look[2] / flat * BURST_FORWARD : 0.0;

		launch(level, player, new Vec3(fx, burstLift(burst), fz));
		// like a mace's wind burst, the landing from this launch does not hurt
		player.resetFallDistance();
		FALLEN.remove(id);

		burstEffects(level, player.getX(), player.getY(), player.getZ(), burst);

		if (!player.isCreative() && wear(stack, player)) {
			stack.shrink(1);
		}
	}

	// ------------------------------------------------------------------ soft landing

	/** Every tick, for every player: how fast they are moving up or down, and how far they have fallen since they last rose or stood. */
	private static void trackFall(ServerPlayer player) {
		UUID id = player.getUUID();
		double y = player.getY();
		Double before = LAST_Y.put(id, y);
		double speed = before == null ? 0.0 : y - before;
		SPEED_Y.put(id, speed);

		if (landed(player) || speed >= 0.0) {
			FALLEN.remove(id);
		} else {
			FALLEN.merge(id, -speed, Double::sum);
		}
	}

	/** How far (blocks) the player would fall in the given number of ticks at their present speed, with gravity and drag. */
	private static double fallIn(double speed, int ticks) {
		double v = speed;
		double distance = 0.0;

		for (int i = 0; i < ticks; i++) {
			distance += Math.max(0.0, -v);
			v = (v - 0.08) * 0.98;
		}

		return distance;
	}

	/** How far below the player's feet the first block they would land on is (blocks), or -1 if there is none in reach. */
	private static double groundBelow(ServerLevel level, ServerPlayer player) {
		int x = (int) Math.floor(player.getX());
		int z = (int) Math.floor(player.getZ());
		int top = (int) Math.floor(player.getY() - 1.0E-3);

		for (int by = top; by >= top - SOFT_LANDING_SCAN_BLOCKS; by--) {
			BlockPos pos = new BlockPos(x, by, z);

			if (!level.hasChunkAt(pos)) {
				return -1.0;
			}

			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
				return player.getY() - (by + 1);
			}
		}

		return -1.0;
	}

	/**
	 * A Riptide + Wind Burst poke made falling, within half a second of the ground (and after a fall that would hurt): the wind
	 * cushions the landing instead of launching. A burst goes off, and the fall damage of the landing that follows is cut by 90%
	 * (see {@link #softLanding}). Costs a use of the spear, like a launch. Returns true when it was a soft landing.
	 */
	private static boolean cushionLanding(ServerLevel level, ServerPlayer player, ItemStack stack, int burst, int now) {
		UUID id = player.getUUID();
		double speed = SPEED_Y.getOrDefault(id, 0.0);

		if (landed(player) || speed > -0.2 || FALLEN.getOrDefault(id, 0.0) <= SOFT_LANDING_MIN_FALL) {
			return false;
		}

		double ground = groundBelow(level, player);

		if (ground < 0.0 || ground > fallIn(speed, SOFT_LANDING_WINDOW_TICKS)) {
			return false;
		}

		if (windBlocked(player)) {
			return false;
		}

		if (SOFT_LANDING_UNTIL.getOrDefault(id, -1) >= now) {
			// already cushioned: another press changes nothing
			return true;
		}

		SOFT_LANDING_UNTIL.put(id, now + SOFT_LANDING_VALID_TICKS);
		burstEffects(level, player.getX(), player.getY(), player.getZ(), burst);

		if (!player.isCreative() && wear(stack, player)) {
			stack.shrink(1);
		}

		return true;
	}

	/**
	 * The fall damage of a landing that was cushioned: the hit is cancelled and 10% of it is dealt instead. (The cushion is spent
	 * first, so the smaller hit is not cut again.) Returns true when the original hit was replaced.
	 */
	private static boolean softLanding(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer player) || !source.is(DamageTypeTags.IS_FALL)) {
			return false;
		}

		Integer until = SOFT_LANDING_UNTIL.remove(player.getUUID());

		if (until == null || !(player.level() instanceof ServerLevel level) || level.getServer().getTickCount() > until) {
			return false;
		}

		level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(), 16, 0.5, 0.1, 0.5, 0.1);
		float rest = amount * (1.0F - SOFT_LANDING_REDUCTION);

		if (rest > 0.0F) {
			player.hurtServer(level, source, rest);
		}

		return true;
	}

	/** True when the stack is a Wind Spear with Wind Burst and without Riptide (the one that blocks and can parry a landing). */
	private static boolean parrySpear(ItemStack stack) {
		return isWindSpear(stack) && levelOf(stack, WIND_BURST) > 0 && levelOf(stack, RIPTIDE) <= 0;
	}

	/**
	 * The moment a block starts: in the air, with a Wind Burst spear that has no Riptide (those have their own soft landing),
	 * the landing that comes within the next FALL_PARRY_WINDOW_TICKS is parried. A block that was already being held does not
	 * pass through here again, so holding it for the whole fall earns nothing.
	 */
	private static void startFallParry(ServerPlayer player, int now) {
		if (!parrySpear(player.getUseItem()) || landed(player)) {
			return;
		}

		FALL_PARRY_UNTIL.put(player.getUUID(), now + FALL_PARRY_WINDOW_TICKS);
	}

	/** Forgets launched entities that have come down (or are gone, or never came down). */
	private static void trackLaunched(MinecraftServer server, int now) {
		if (LAUNCHED.isEmpty()) {
			return;
		}

		Iterator<Map.Entry<UUID, Integer>> it = LAUNCHED.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<UUID, Integer> entry = it.next();
			Entity entity = null;

			for (ServerLevel level : server.getAllLevels()) {
				entity = level.getEntity(entry.getKey());

				if (entity != null) {
					break;
				}
			}

			int age = now - entry.getValue();

			// the first few ticks are still on the ground (the launch itself)
			if (entity == null || entity.isRemoved() || age > LAUNCH_TIMEOUT_TICKS
					|| (age > 5 && (entity.onGround() || entity.isInWater() || entity.isInLava() || entity.isPassenger()))) {
				it.remove();
			}
		}
	}

	/**
	 * Fall damage cut for two cases (the better cut wins, they do not stack): a landing parried by a blocking Wind Burst spear
	 * (75%), and the landing of an entity a blocking Wind Burst spear threw up (50%). The hit is cancelled and the smaller
	 * one is dealt in its place (the flags are spent first, so the smaller hit is not cut again). Returns true when the
	 * original hit was replaced.
	 */
	private static boolean fallReduction(LivingEntity entity, DamageSource source, float amount) {
		if (!source.is(DamageTypeTags.IS_FALL) || !(entity.level() instanceof ServerLevel level)) {
			return false;
		}

		UUID id = entity.getUUID();
		int now = level.getServer().getTickCount();
		float cut = 0.0F;

		if (entity instanceof ServerPlayer player) {
			Integer until = FALL_PARRY_UNTIL.remove(id);

			if (until != null && until >= now && (parrySpear(player.getMainHandItem()) || parrySpear(player.getOffhandItem()))) {
				cut = FALL_PARRY_REDUCTION;
			}
		}

		if (LAUNCHED.remove(id) != null) {
			cut = Math.max(cut, LAUNCHED_FALL_REDUCTION);
		}

		if (cut <= 0.0F) {
			return false;
		}

		level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.1, entity.getZ(), 16, 0.5, 0.1, 0.5, 0.1);
		float rest = amount * (1.0F - cut);

		if (rest > 0.0F) {
			entity.hurtServer(level, source, rest);
		}

		return true;
	}

	/** A spear with Wind Burst but without Riptide, the moment its holder starts to block: whatever they look at is thrown up. */
	private static void blockBurst(ServerPlayer player, int now) {
		ItemStack stack = player.getUseItem();

		if (!isWindSpear(stack) || !(player.level() instanceof ServerLevel level)) {
			return;
		}

		int burst = levelOf(stack, WIND_BURST);

		if (burst <= 0) {
			return;
		}

		boolean cooling = NEXT_BURST.getOrDefault(player.getUUID(), 0) > now;
		Entity target = cooling ? null : lookedAt(level, player);

		// a line in the log about once a second while blocking, so a launch that does not happen can be traced
		if (now % 20 == 0) {
			SkyIslands.LOGGER.info("Sky Islands: Wind Burst {} block: cooling down={}, target={}", burst, cooling,
					target == null ? "none" : target.getClass().getSimpleName());
		}

		// nothing in reach (or still cooling down): no launch, and no cooldown or durability spent
		if (cooling || target == null) {
			return;
		}

		NEXT_BURST.put(player.getUUID(), now + BURST_COOLDOWN_TICKS);

		Vec3 motion = target.getDeltaMovement();
		launch(level, target, new Vec3(motion.x, burstLift(burst), motion.z));
		burstEffects(level, target.getX(), target.getY(), target.getZ(), burst);

		// whatever was thrown up only takes half the fall damage when it lands
		if (target instanceof LivingEntity) {
			LAUNCHED.put(target.getUUID(), now);
		}

		if (!player.isCreative() && wear(stack, player)) {
			stack.shrink(1);
		}
	}

	/** The nearest entity the player is looking at within reach, with no solid block in between (null if none). */
	private static Entity lookedAt(ServerLevel level, ServerPlayer player) {
		double[] look = lookOf(player);
		Vec3 from = player.getEyePosition();
		Vec3 to = from.add(look[0] * BURST_REACH, look[1] * BURST_REACH, look[2] * BURST_REACH);

		// how far the view goes before a solid block stops it
		double reach = BURST_REACH;

		for (double d = 0.25; d <= BURST_REACH; d += 0.25) {
			BlockPos pos = new BlockPos((int) Math.floor(from.x + look[0] * d), (int) Math.floor(from.y + look[1] * d), (int) Math.floor(from.z + look[2] * d));

			if (level.hasChunkAt(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
				reach = d;
				break;
			}
		}

		Entity best = null;
		double bestDistance = reach;

		for (Entity entity : level.getEntitiesOfClass(Entity.class, player.getBoundingBox().expandTowards(to.subtract(from)).inflate(1.0))) {
			if (entity == player || entity.isRemoved() || entity.isSpectator() || !entity.isPickable()) {
				continue;
			}

			Optional<Vec3> hit = entity.getBoundingBox().inflate(entity.getPickRadius() + 0.3).clip(from, to);

			if (hit.isEmpty()) {
				continue;
			}

			double distance = from.distanceTo(hit.get());

			if (distance <= bestDistance) {
				best = entity;
				bestDistance = distance;
			}
		}

		return best;
	}

	private static void burstEffects(ServerLevel level, double x, double y, double z, int burst) {
		level.sendParticles(ParticleTypes.GUST, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0);
		level.sendParticles(ParticleTypes.CLOUD, x, y + 0.2, z, 12 + 4 * burst, 0.4, 0.1, 0.4, 0.08);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:entity.wind_charge.wind_burst player @a %.1f %.1f %.1f 1.0 1.0", x, y, z));
	}

	// ------------------------------------------------------------------ a spear in flight

	private static final class Thrown {
		final ServerLevel level;
		final UUID owner;
		final ItemStack stack;
		final String tier;
		final boolean creative;
		final float damage;
		final int loyalty;
		int pierceLeft;
		final Set<UUID> hit = new HashSet<>();
		UUID id;
		double x;
		double y;
		double z;
		double vx;
		double vy;
		double vz;
		double lastFreeX;
		double lastFreeY;
		double lastFreeZ;
		int age;
		boolean returning;
		int returnWait;
		/** Stuck in a block, like a trident (it can then be picked up, or with Loyalty it flies home). */
		boolean stuck;
		/** Thrown by a mob (a drowned): only hurts players, is only a copy, never picked up. */
		boolean mob;
		/** It has hit something and bounced off: it falls, and hurts nothing more (like a trident that has dealt its damage). */
		boolean spent;
		int stuckTicks;
		BlockPos stuckBlock;

		Thrown(ServerLevel level, UUID owner, ItemStack stack, boolean creative, double x, double y, double z, double vx, double vy, double vz) {
			this.level = level;
			this.owner = owner;
			this.stack = stack;
			this.creative = creative;
			this.tier = tierOf(stack);
			int sharpness = levelOf(stack, "minecraft:sharpness");
			this.damage = baseDamage(stack) + (sharpness > 0 ? 0.5F * sharpness + 0.5F : 0.0F);
			this.loyalty = levelOf(stack, LOYALTY);
			this.pierceLeft = levelOf(stack, PIERCING);
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

	private static void tick(int now) {
		Iterator<PendingCooldown> pending = PENDING.iterator();

		while (pending.hasNext()) {
			PendingCooldown cooldown = pending.next();

			if (now < cooldown.at) {
				continue;
			}

			int remaining = cooldown.end - now;

			if (remaining > 0 && cooldown.left > 0 && !cooldown.player.isRemoved()) {
				showCooldown(cooldown.player, cooldown.stack, remaining);
				cooldown.at += 2;
				cooldown.left--;
			} else {
				pending.remove();
			}
		}

		Iterator<Thrown> it = THROWN.iterator();

		while (it.hasNext()) {
			Thrown spear = it.next();

			if (!advance(spear)) {
				it.remove();
				SpearVisual.remove(spear.level, spear.id);
			}
		}
	}

	/** Returns false when the spear is finished (picked up again, dropped, or lost). */
	private static boolean advance(Thrown spear) {
		ServerLevel level = spear.level;

		// Loyalty: a spear that gets more than LOYALTY_RECALL_BLOCKS from its owner comes back at once, into their inventory
		if (spear.loyalty > 0 && !spear.creative && !spear.mob) {
			Player owner = level.getPlayerByUUID(spear.owner);

			if (owner != null && owner.isAlive() && owner.distanceToSqr(spear.x, spear.y, spear.z) > LOYALTY_RECALL_BLOCKS * LOYALTY_RECALL_BLOCKS) {
				level.sendParticles(ParticleTypes.CLOUD, spear.x, spear.y, spear.z, 8, 0.2, 0.2, 0.2, 0.05);
				giveBack(spear, owner);
				return false;
			}
		}

		if (spear.stuck) {
			return stuckTick(spear);
		}

		spear.age++;

		if (spear.age > MAX_AGE * 3) {
			drop(spear, spear.x, spear.y, spear.z);
			return false;
		}

		if (spear.age > MAX_AGE && !spear.returning) {
			return finish(spear, spear.x, spear.y, spear.z);
		}

		if (spear.returning) {
			return flyHome(spear);
		}

		spear.vy -= spear.spent ? SPENT_GRAVITY : GRAVITY;

		// like an arrow, a spear loses speed in liquid
		BlockPos here = new BlockPos((int) Math.floor(spear.x), (int) Math.floor(spear.y), (int) Math.floor(spear.z));

		if (level.hasChunkAt(here)) {
			FluidState fluid = level.getBlockState(here).getFluidState();

			if (!fluid.isEmpty()) {
				double drag = fluid.is(FluidTags.LAVA) ? LAVA_DRAG : WATER_DRAG;
				spear.vx *= drag;
				spear.vy *= drag;
				spear.vz *= drag;

				if (fluid.is(FluidTags.WATER)) {
					level.sendParticles(ParticleTypes.BUBBLE, spear.x, spear.y, spear.z, 3, 0.1, 0.1, 0.1, 0.02);
				}
			}
		}

		double length = Math.sqrt(spear.vx * spear.vx + spear.vy * spear.vy + spear.vz * spear.vz);
		int steps = Math.max(1, (int) Math.ceil(length / 0.4));

		for (int i = 1; i <= steps; i++) {
			double px = spear.x + spear.vx * i / steps;
			double py = spear.y + spear.vy * i / steps;
			double pz = spear.z + spear.vz * i / steps;
			BlockPos block = new BlockPos((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));

			if (!level.hasChunkAt(block)) {
				return finish(spear, spear.lastFreeX, spear.lastFreeY, spear.lastFreeZ);
			}

			BlockState state = level.getBlockState(block);

			if (!state.getCollisionShape(level, block).isEmpty()) {
				level.sendParticles(ParticleTypes.CRIT, px, py, pz, 6, 0.1, 0.1, 0.1, 0.15);
				ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
						"playsound minecraft:item.trident.hit_ground player @a %.1f %.1f %.1f 1.0 1.0", px, py, pz));
				return stick(spear, block, px, py, pz);
			}

			spear.lastFreeX = px;
			spear.lastFreeY = py;
			spear.lastFreeZ = pz;

			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(px - 1.5, py - 1.5, pz - 1.5, px + 1.5, py + 1.5, pz + 1.5))) {
				if (spear.spent || target.getUUID().equals(spear.owner) || !target.isAlive() || spear.hit.contains(target.getUUID())
						|| (target instanceof ServerPlayer other && other.isSpectator())
						|| (spear.mob && !(target instanceof Player))) {
					continue;
				}

				if (!target.getBoundingBox().inflate(0.3).contains(px, py, pz)) {
					continue;
				}

				spear.hit.add(target.getUUID());
				hitEntity(spear, target, px, py, pz);

				if (spear.pierceLeft > 0) {
					spear.pierceLeft--;
				} else if (spear.loyalty > 0) {
					return finish(spear, px, py, pz);
				} else {
					return bounce(spear, px, py, pz);
				}
			}
		}

		spear.x += spear.vx;
		spear.y += spear.vy;
		spear.z += spear.vz;
		SpearVisual.move(level, spear.id, spear.x, spear.y, spear.z, spear.vx, spear.vy, spear.vz, spear.age);
		return true;
	}

	private static void hitEntity(Thrown spear, LivingEntity target, double x, double y, double z) {
		ServerLevel level = spear.level;
		level.sendParticles(ParticleTypes.CRIT, x, y, z, 10, 0.2, 0.2, 0.2, 0.25);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:item.trident.hit player @a %.1f %.1f %.1f 1.0 1.0", x, y, z));

		Entity owner = level.getEntity(spear.owner);
		String by = owner != null ? " by " + spear.owner : "";
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"damage %s %.1f minecraft:trident%s", target.getUUID(), spear.damage, by));
	}

	/**
	 * A spear without Loyalty has hit an entity: like a trident, it bounces off a little and falls. It lands in the first block it
	 * meets (see {@link #stick}) and can be picked up from there, instead of turning into an item on the spot.
	 */
	private static boolean bounce(Thrown spear, double x, double y, double z) {
		spear.spent = true;
		spear.x = x;
		spear.y = y;
		spear.z = z;
		spear.lastFreeX = x;
		spear.lastFreeY = y;
		spear.lastFreeZ = z;
		spear.vx *= BOUNCE_SIDEWAYS;
		spear.vy *= BOUNCE_VERTICAL;
		spear.vz *= BOUNCE_SIDEWAYS;
		SpearVisual.move(spear.level, spear.id, x, y, z, spear.vx, spear.vy, spear.vz, spear.age);
		return true;
	}

	/** The spear hit a block: it sticks in it, pointing the way it was flying (like a trident). */
	private static boolean stick(Thrown spear, BlockPos block, double hitX, double hitY, double hitZ) {
		double length = Math.sqrt(spear.vx * spear.vx + spear.vy * spear.vy + spear.vz * spear.vz);

		if (length < 1.0E-6) {
			return finish(spear, spear.lastFreeX, spear.lastFreeY, spear.lastFreeZ);
		}

		double dx = spear.vx / length;
		double dy = spear.vy / length;
		double dz = spear.vz / length;

		// The surface is between the last free point and the first point inside the block. The picture's centre sits behind
		// the surface so that the tip is buried in the block and the rest sticks out.
		double surfaceX = (spear.lastFreeX + hitX) / 2.0;
		double surfaceY = (spear.lastFreeY + hitY) / 2.0;
		double surfaceZ = (spear.lastFreeZ + hitZ) / 2.0;

		spear.x = surfaceX - dx * 0.6;
		spear.y = surfaceY - dy * 0.6;
		spear.z = surfaceZ - dz * 0.6;
		spear.vx = dx;
		spear.vy = dy;
		spear.vz = dz;
		spear.stuck = true;
		spear.stuckTicks = 0;
		spear.stuckBlock = block;

		SpearVisual.move(spear.level, spear.id, spear.x, spear.y, spear.z, dx, dy, dz, 0);
		return true;
	}

	/** A stuck spear: waits to be picked up, flies home with Loyalty, or falls if the block it is stuck in is gone. */
	private static boolean stuckTick(Thrown spear) {
		ServerLevel level = spear.level;
		spear.stuckTicks++;

		if (level.hasChunkAt(spear.stuckBlock) && level.getBlockState(spear.stuckBlock).getCollisionShape(level, spear.stuckBlock).isEmpty()) {
			if (!spear.creative) {
				drop(spear, spear.x, spear.y, spear.z);
			}

			return false;
		}

		if (spear.stuckTicks < STUCK_DELAY_TICKS) {
			return true;
		}

		if (spear.mob) {
			return spear.stuckTicks <= MOB_STUCK_TICKS;
		}

		Player owner = level.getPlayerByUUID(spear.owner);

		if (spear.loyalty > 0 && !spear.creative && owner != null && owner.isAlive()) {
			spear.stuck = false;
			spear.returning = true;
			spear.returnWait = 3;
			return true;
		}

		if (spear.creative && spear.stuckTicks > CREATIVE_STUCK_TICKS) {
			return false;
		}

		for (ServerPlayer player : level.players()) {
			if (player.isSpectator() || !player.isAlive() || !player.getBoundingBox().inflate(0.6).contains(spear.x, spear.y, spear.z)) {
				continue;
			}

			if (spear.creative) {
				// only a copy: its owner just takes it back, nothing is given
				if (player.getUUID().equals(spear.owner)) {
					return false;
				}

				continue;
			}

			if (player.getInventory().add(spear.stack)) {
				ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
						"playsound minecraft:entity.item.pickup player @a %.1f %.1f %.1f 0.4 1.4", player.getX(), player.getY(), player.getZ()));
				return false;
			}
		}

		return true;
	}

	/** The server is stopping: the pictures cannot survive a restart, so every spear still out becomes a real item where it is. */
	private static void dropAll() {
		for (Thrown spear : THROWN) {
			if (!spear.creative) {
				drop(spear, spear.x, spear.y, spear.z);
			}

			SpearVisual.remove(spear.level, spear.id);
		}

		THROWN.clear();
	}

	/**
	 * The spear has hit something or run out of flight: with Loyalty it flies back to its owner, otherwise it drops where it
	 * is. (A creative player's spear is only a copy and just vanishes.) Returns true while it still has to be kept flying.
	 */
	private static boolean finish(Thrown spear, double x, double y, double z) {
		if (spear.creative) {
			return false;
		}

		if (spear.loyalty > 0 && spear.level.getPlayerByUUID(spear.owner) != null) {
			spear.returning = true;
			spear.returnWait = 6;
			spear.x = x;
			spear.y = y;
			spear.z = z;
			return true;
		}

		drop(spear, x, y, z);
		return false;
	}

	private static void drop(Thrown spear, double x, double y, double z) {
		ItemEntity item = new ItemEntity(spear.level, x, y, z, spear.stack);
		item.setPickUpDelay(10);
		spear.level.addFreshEntity(item);
	}

	/** The spear goes into its owner's inventory (or drops at their feet if it is full) with the trident's return sound. */
	private static void giveBack(Thrown spear, Player owner) {
		ServerLevel level = spear.level;

		if (!owner.getInventory().add(spear.stack)) {
			drop(spear, owner.getX(), owner.getY() + 0.5, owner.getZ());
		}

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:item.trident.return player @a %.1f %.1f %.1f 1.0 1.0", owner.getX(), owner.getY(), owner.getZ()));
	}

	/** Loyalty: the spear turns around and flies back to its owner, whose inventory gets it again. */
	private static boolean flyHome(Thrown spear) {
		ServerLevel level = spear.level;
		Player owner = level.getPlayerByUUID(spear.owner);

		if (owner == null || !owner.isAlive()) {
			drop(spear, spear.x, spear.y, spear.z);
			return false;
		}

		if (spear.returnWait > 0) {
			spear.returnWait--;
			SpearVisual.move(level, spear.id, spear.x, spear.y, spear.z, spear.vx, spear.vy, spear.vz, spear.age);
			return true;
		}

		double dx = owner.getX() - spear.x;
		double dy = owner.getY() + 1.0 - spear.y;
		double dz = owner.getZ() - spear.z;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (distance < 1.6) {
			giveBack(spear, owner);
			return false;
		}

		double speed = Math.min(distance, 0.6 + 0.5 * spear.loyalty);
		spear.vx = dx / distance * speed;
		spear.vy = dy / distance * speed;
		spear.vz = dz / distance * speed;
		spear.x += spear.vx;
		spear.y += spear.vy;
		spear.z += spear.vz;
		SpearVisual.move(level, spear.id, spear.x, spear.y, spear.z, spear.vx, spear.vy, spear.vz, spear.age);
		return true;
	}
}
