package com.skyislands;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The Prismarine Stick and the Prismarine Axe.
 *
 * <p><b>Stick</b>: a plain item, crafted from 2 prismarine shards (data/sky_islands/recipe/prismarine_stick.json).
 *
 * <p><b>Axe</b>: a real axe (built with {@code Item.Properties.axe}) (so it strips logs and scrapes copper like any axe, and mines and takes
 * Efficiency like one), one step above netherite in damage (11 instead of 10) and with a base attack speed of 0.8 (the same as a wooden axe).
 * Which enchantments it accepts is decided by item tags, in data/minecraft/tags/item/enchantable and
 * data/sky_islands/tags/item/enchantable:
 * <ul>
 * <li><b>Mending, Unbreaking, Efficiency</b>: vanilla, allowed by adding the axe to the vanilla enchantable tags.</li>
 * <li><b>Impaling</b>: allowed through data/minecraft/enchantment/impaling.json (overridden), which also gives it the
 * Sharpness damage curve (+1.0 at level I, +0.5 more per level) when the weapon is a Prismarine Axe. Tridents are
 * unchanged.</li>
 * <li><b>Density</b>: allowed through data/minecraft/enchantment/density.json (overridden) and made to work here: while
 * falling (more than 1.5 blocks, not gliding) a hit adds the enchantment's per-fallen-block damage times the distance
 * fallen, exactly the Density part of a mace smash attack. See {@link PrismarineAxeItem#getAttackDamageBonus}.</li>
 * <li><b>Sweeping Edge</b>: vanilla allows it on swords only, and only swords sweep. Here an axe with Sweeping Edge
 * sweeps like a sword (see {@link #sweep}).</li>
 * </ul>
 * Efficiency also changes the attack speed (see {@link #updateEfficiency}).
 */
public final class PrismarineItems {
	/** Repair material for the axe (an anvil combines it with prismarine shards). */
	public static final TagKey<Item> REPAIR_TAG = TagKey.create(Registries.ITEM, SkyIslands.id("prismarine_tool_materials"));

	/** The attack damage the axe adds, on top of the 5 every axe has: netherite adds 4, prismarine 5 (so 11 total vs. netherite's 10). */
	private static final float MATERIAL_DAMAGE_BONUS = 5.0F;
	/** Base damage modifier of a vanilla axe (the netherite axe uses it too). */
	private static final float AXE_BASE_DAMAGE = 5.0F;
	/** A player's attack speed is 4.0 before the weapon; the axe's modifier makes it 0.8, the same as a wooden axe. */
	private static final float AXE_ATTACK_SPEED_MODIFIER = 0.8F - 4.0F;

	/**
	 * Attack speed added per level of Efficiency while a Prismarine Axe is held. POSITIVE = faster (0.8 -> 0.9 -> 1.0 ...).
	 * Make it negative for an Efficiency that swings slower instead.
	 */
	private static final double EFFICIENCY_ATTACK_SPEED_PER_LEVEL = 0.1;
	private static final Identifier MINING_CANCEL_ID = SkyIslands.id("prismarine_axe_no_efficiency_mining");

	/** A fall must be longer than this (blocks) for Density to count: same as the mace's smash attack. */
	private static final float SMASH_FALL_THRESHOLD = 1.5F;

	/** An axe only sweeps when it has Sweeping Edge (set to false and every Prismarine Axe sweeps for 1 damage, like a plain sword). */
	private static final boolean SWEEP_NEEDS_ENCHANTMENT = true;
	/** A swing must be at least this charged to sweep (a sword needs 90%). */
	private static final float SWEEP_MIN_CHARGE = 0.9F;

	/** Netherite's mining speed (9) and enchantability (15) with 5 extra attack damage, but the stone axe's durability (131 uses). */
	private static final ToolMaterial MATERIAL = new ToolMaterial(
		BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 131, 9.0F, MATERIAL_DAMAGE_BONUS, 15, REPAIR_TAG);

	public static Item PRISMARINE_STICK;
	public static Item PRISMARINE_AXE;

	/** How charged each player's last attack swing was (read just before the hit, because the hit resets the charge). */
	private static final Map<UUID, Float> ATTACK_CHARGE = new HashMap<>();
	/** True while the extra sweep hits are being dealt, so they do not sweep again. */
	private static boolean sweeping = false;

	private PrismarineItems() { }

	public static void register() {
		Identifier stickId = SkyIslands.id("prismarine_stick");
		Item.Properties stickProperties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, stickId));
		stickProperties.component(DataComponents.ITEM_MODEL, stickId);
		stickProperties.component(DataComponents.ITEM_NAME, Component.translatable("item.sky_islands.prismarine_stick"));
		PRISMARINE_STICK = Registry.register(BuiltInRegistries.ITEM, stickId, new Item(stickProperties));

		Identifier axeId = SkyIslands.id("prismarine_axe");
		Item.Properties axeProperties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, axeId));
		axeProperties.component(DataComponents.ITEM_MODEL, axeId);
		axeProperties.component(DataComponents.ITEM_NAME, Component.translatable("item.sky_islands.prismarine_axe"));
		PRISMARINE_AXE = Registry.register(BuiltInRegistries.ITEM, axeId,
			new PrismarineAxeItem(axeProperties.axe(MATERIAL, AXE_BASE_DAMAGE, AXE_ATTACK_SPEED_MODIFIER)));

		// creative tabs: the stick next to the vanilla stick, the axe right after the netherite axe
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(output -> output.insertAfter(Items.STICK, PRISMARINE_STICK));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> output.insertAfter(Items.NETHERITE_AXE, PRISMARINE_AXE));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.insertAfter(Items.NETHERITE_AXE, PRISMARINE_AXE));

		// Efficiency changes the attack speed of whoever holds the axe
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			boolean whole = server.getTickCount() % 20 == 0;

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				updateEfficiency(player, whole);
			}
		});

		// Sweeping Edge: remember how charged the swing was (it is reset by the time the hit lands), then sweep after the hit
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (player instanceof ServerPlayer) {
				ATTACK_CHARGE.put(player.getUUID(), player.getAttackStrengthScale(0.5F));
			}

			return InteractionResult.PASS;
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (!blocked && !sweeping) {
				sweep(entity, source);
			}
		});
	}

	public static void clear() {
		ATTACK_CHARGE.clear();
		sweeping = false;
	}

	static boolean isPrismarineAxe(ItemStack stack) {
		return PRISMARINE_AXE != null && stack.is(PRISMARINE_AXE);
	}

	/** The level of an enchantment on a stack, by its id such as "minecraft:efficiency" (0 if it is not there). */
	private static int levelOf(ItemStack stack, String enchantment) {
		ItemEnchantments enchantments = stack.getEnchantments();

		for (Holder<Enchantment> holder : enchantments.keySet()) {
			if (holder.getRegisteredName().equals(enchantment)) {
				return enchantments.getLevel(holder);
			}
		}

		return 0;
	}

	// ------------------------------------------------------------------ efficiency

	/**
	 * Efficiency on a Prismarine Axe changes how fast it swings, not how fast it mines:
	 * <ul>
	 * <li>The axe's own attack speed modifier (on the item, so the tooltip and the cooldown both use it) is rewritten to
	 * 0.8 + {@link #EFFICIENCY_ATTACK_SPEED_PER_LEVEL} x level: Efficiency I = 0.9, II = 1.0 ... Axes in the main hand and
	 * off hand are kept up to date every tick, the rest of the inventory once a second.</li>
	 * <li>Vanilla Efficiency's mining boost (level squared + 1, an attribute on the player) is cancelled with an equal
	 * negative transient modifier while a Prismarine Axe with Efficiency is in the main hand.</li>
	 * </ul>
	 */
	private static void updateEfficiency(ServerPlayer player, boolean wholeInventory) {
		ItemStack held = player.getMainHandItem();
		syncAttackSpeed(held);
		syncAttackSpeed(player.getOffhandItem());

		if (wholeInventory) {
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				syncAttackSpeed(player.getInventory().getItem(i));
			}
		}

		AttributeInstance mining = player.getAttribute(Attributes.MINING_EFFICIENCY);

		if (mining == null) {
			return;
		}

		AttributeModifier current = mining.getModifier(MINING_CANCEL_ID);
		double without = mining.getValue() - (current == null ? 0.0 : current.amount());

		if (!isPrismarineAxe(held) || levelOf(held, "minecraft:efficiency") <= 0 || without <= 0.0) {
			if (current != null) {
				mining.removeModifier(MINING_CANCEL_ID);
			}

			return;
		}

		if (current == null || Math.abs(current.amount() + without) > 1.0E-6) {
			mining.addOrUpdateTransientModifier(new AttributeModifier(MINING_CANCEL_ID, -without, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	/** Makes the axe's attack speed modifier match its Efficiency level (does nothing for other items or when it already matches). */
	private static void syncAttackSpeed(ItemStack stack) {
		if (!isPrismarineAxe(stack)) {
			return;
		}

		double target = AXE_ATTACK_SPEED_MODIFIER + EFFICIENCY_ATTACK_SPEED_PER_LEVEL * levelOf(stack, "minecraft:efficiency");
		ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		boolean differs = false;

		for (ItemAttributeModifiers.Entry entry : current.modifiers()) {
			if (entry.attribute().equals(Attributes.ATTACK_SPEED) && Math.abs(entry.modifier().amount() - target) > 1.0E-6) {
				differs = true;
			}
		}

		if (!differs) {
			return;
		}

		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();

		for (ItemAttributeModifiers.Entry entry : current.modifiers()) {
			AttributeModifier modifier = entry.modifier();

			if (entry.attribute().equals(Attributes.ATTACK_SPEED)) {
				modifier = new AttributeModifier(modifier.id(), target, modifier.operation());
			}

			builder.add(entry.attribute(), modifier, entry.slot());
		}

		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
	}

	// ------------------------------------------------------------------ sweeping edge

	/**
	 * A sword's sweep, for the axe: after a full-strength standing hit (on the ground, not sprinting) with a Prismarine
	 * Axe, everything within 1 block of the target (and 3 of the player) takes 1 + (Sweeping Edge ratio x attack damage).
	 * The same rules and numbers as vanilla's sword sweep, including the knockback, sound and slash particle.
	 */
	private static void sweep(LivingEntity target, DamageSource source) {
		if (!(source.getDirectEntity() instanceof ServerPlayer player) || source.getEntity() != player || !source.is(DamageTypes.PLAYER_ATTACK)) {
			return;
		}

		ItemStack held = player.getMainHandItem();

		if (!isPrismarineAxe(held) || !(target.level() instanceof ServerLevel level)) {
			return;
		}

		if (SWEEP_NEEDS_ENCHANTMENT && levelOf(held, "minecraft:sweeping_edge") <= 0) {
			return;
		}

		Float charge = ATTACK_CHARGE.remove(player.getUUID());

		if (charge == null || charge < SWEEP_MIN_CHARGE || !player.onGround() || player.isSprinting()) {
			return;
		}

		float baseDamage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		float sweepDamage = 1.0F + (float) player.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO) * baseDamage;
		float yawRadians = player.getYRot() * ((float) Math.PI / 180.0F);
		sweeping = true;

		try {
			for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(1.0, 0.25, 1.0))) {
				if (other == player || other == target || !other.isAlive() || !other.isAttackable() || player.isAlliedTo(other) || player.distanceToSqr(other) >= 9.0) {
					continue;
				}

				if (other.hurtServer(level, level.damageSources().playerAttack(player), sweepDamage)) {
					double push = 0.4 * (1.0 - other.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
					other.push(-Mth.sin(yawRadians) * push, 0.1, Mth.cos(yawRadians) * push);
				}
			}
		} finally {
			sweeping = false;
		}

		Object sound = SoundEvents.PLAYER_ATTACK_SWEEP;
		SoundEvent sweepSound = sound instanceof Holder<?> holder ? (SoundEvent) holder.value() : (SoundEvent) sound;
		level.playSound(null, player.getX(), player.getY(), player.getZ(), sweepSound, SoundSource.PLAYERS, 1.0F, 1.0F);
		double dx = -Mth.sin(yawRadians);
		double dz = Mth.cos(yawRadians);
		level.sendParticles(ParticleTypes.SWEEP_ATTACK, player.getX() + dx, player.getY(0.5), player.getZ() + dz, 0, dx, 0.0, dz, 0.0);
	}

	// ------------------------------------------------------------------ the item

	/** An axe that adds the Density enchantment's falling damage to a hit, the way a mace does. */
	static final class PrismarineAxeItem extends Item {
		PrismarineAxeItem(Item.Properties properties) {
			super(properties);
		}

		/**
		 * Density: while falling more than 1.5 blocks (and not gliding with an elytra) a hit does an extra
		 * (Density's damage per fallen block) x (blocks fallen). Without Density it is 0, so a plain axe hit is unchanged:
		 * the mace's big base smash damage is NOT copied, only what the enchantment adds.
		 */
		// no @Override on purpose: if this version no longer has the method, the build still works (and Density then does nothing)
		public float getAttackDamageBonus(Entity target, float baseDamage, DamageSource damageSource) {
			if (damageSource.getDirectEntity() instanceof LivingEntity attacker
				&& attacker.level() instanceof ServerLevel level
				&& (float) attacker.fallDistance > SMASH_FALL_THRESHOLD
				&& !attacker.isFallFlying()) {
				ItemStack weapon = attacker.getWeaponItem();

				if (isPrismarineAxe(weapon)) {
					return EnchantmentHelper.modifyFallBasedDamage(level, weapon, target, damageSource, 0.0F) * (float) attacker.fallDistance;
				}
			}

			return 0.0F;
		}
	}
}
