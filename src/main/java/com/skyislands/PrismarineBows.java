package com.skyislands;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The Prismarine Arrow and the Prismarine Bow.
 *
 * <p><b>Arrow</b>: a real {@link ArrowItem} (so it makes an ordinary arrow entity that carries the Prismarine Arrow as its
 * pick-up item), listed in the {@code minecraft:arrows} item tag, which is what lets the vanilla bow and crossbow shoot it.
 * Two things set it apart, both worked out in this class because the arrow entity is the vanilla one:
 * <ul>
 * <li>Shot from anything but the Prismarine Bow, it leaves at {@link #ARROW_RANGE_FACTOR} of the normal speed, so it does
 * not carry as far (and hits a little softer). Done when the arrow enters the world.</li>
 * <li>It shatters when it lands in a block (it never stays stuck, and can never be picked up again). It is checked once a
 * tick: an arrow that has stopped in the ground is removed with a puff and a glass sound.</li>
 * </ul>
 *
 * <p><b>Bow</b>: a {@link BowItem} that only takes Prismarine Arrows, with
 * <ul>
 * <li>twice the damage ({@link #BOW_DAMAGE_MULTIPLIER}): every arrow it shoots is marked, and
 * {@code PrismarineBowDamageMixin} doubles the damage such an arrow deals (Power, Impaling and a critical hit included);</li>
 * <li>half the durability of a vanilla bow ({@link #BOW_DURABILITY} instead of 384): the same thing as breaking twice as fast;</li>
 * <li>a draw that takes {@link #DRAW_TIME_MULTIPLIER} times as long (40 ticks to full power instead of 20), see
 * {@link PrismarineBowItem#releaseUsing} (and the pull textures in items/prismarine_bow.json, which use half the vanilla scale);</li>
 * <li>Impaling as an extra Power level: Impaling is allowed on the bow through the tag
 * data/sky_islands/tags/item/enchantable/impaling.json, and data/minecraft/enchantment/impaling.json gives it
 * +0.5 arrow damage per level (what one level of Power is worth) on a bow. The other bow enchantments (Power, Punch, Flame,
 * Infinity, Mending, Unbreaking) come from the vanilla item tags, to which the bow is added in data/minecraft/tags/item/enchantable.</li>
 * <li>Multishot: vanilla's bow code already handles the extra arrows (the draw and the spread are shared with the crossbow),
 * the enchantment is only limited to crossbows by its item tag. data/minecraft/enchantment/multishot.json (overridden) takes
 * #sky_islands:enchantable/multishot instead, which is the crossbow tag plus this bow. It still excludes Piercing.</li>
 * </ul>
 */
public final class PrismarineBows {
	/** Arrows shot from anything but the Prismarine Bow leave at this part of the normal speed (less range, a little less damage). */
	static final float ARROW_RANGE_FACTOR = 0.75F;
	/** Final damage of an arrow from the Prismarine Bow, as a multiple of what the same arrow does from a normal bow. */
	static final float BOW_DAMAGE_MULTIPLIER = 2.0F;
	/** The vanilla bow has 384 uses; half as many is the same as wearing out twice as fast, whatever Unbreaking does. */
	private static final int BOW_DURABILITY = 192;
	/** The Prismarine Bow takes this many times as long to draw (20 ticks to full power in vanilla, so 40 here). */
	private static final int DRAW_TIME_MULTIPLIER = 2;

	/** Scoreboard tag on every Prismarine Arrow entity (saved with the arrow, so it survives a reload). */
	static final String ARROW_TAG = "sky_islands_prismarine_arrow";
	/** Scoreboard tag on an arrow that was shot from the Prismarine Bow (its damage is doubled). */
	static final String BOW_ARROW_TAG = "sky_islands_prismarine_bow_arrow";

	public static Item PRISMARINE_ARROW;
	public static Item PRISMARINE_BOW;

	/** True while the Prismarine Bow is letting an arrow go (the arrow enters the world inside that call). */
	private static boolean shootingFromBow = false;
	/** Used by the damage mixin: true while the doubled hit is being dealt, so it is not doubled again. */
	public static boolean doubling = false;

	/** Prismarine Arrows that are in the world, checked every tick for having hit a block. */
	private static final List<Entity> IN_FLIGHT = new ArrayList<>();
	/** Where each of them was a tick ago (only used when the arrow's "in ground" flag cannot be read). */
	private static final Map<Entity, Vec3> LAST_POSITION = new IdentityHashMap<>();

	private static final Predicate<ItemStack> PRISMARINE_ARROW_ONLY = stack -> PRISMARINE_ARROW != null && stack.is(PRISMARINE_ARROW);

	/** The item-stack fields of the arrow entity class (one of them is the stack it gives back when picked up). Found once, by type, so no field name matters. */
	private static List<Field> stackFields;
	/** The arrow entity's "stuck in a block" flag, if this version has it under that name. */
	private static Field inGroundField;
	private static boolean inGroundSearched = false;
	/** The field that tells the server to send the arrow's new speed to the players ("needsSync" or the older "hurtMarked"). */
	private static Field syncField;
	private static boolean syncSearched = false;

	private PrismarineBows() { }

	public static void register() {
		Identifier arrowId = SkyIslands.id("prismarine_arrow");
		Item.Properties arrowProperties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, arrowId));
		arrowProperties.component(DataComponents.ITEM_MODEL, arrowId);
		arrowProperties.component(DataComponents.ITEM_NAME, Component.translatable("item.sky_islands.prismarine_arrow"));
		PRISMARINE_ARROW = Registry.register(BuiltInRegistries.ITEM, arrowId, new ArrowItem(arrowProperties));

		Identifier bowId = SkyIslands.id("prismarine_bow");
		Item.Properties bowProperties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, bowId)).durability(BOW_DURABILITY).enchantable(1);
		bowProperties.component(DataComponents.ITEM_MODEL, bowId);
		bowProperties.component(DataComponents.ITEM_NAME, Component.translatable("item.sky_islands.prismarine_bow"));
		PRISMARINE_BOW = Registry.register(BuiltInRegistries.ITEM, bowId, new PrismarineBowItem(bowProperties));

		// creative tabs: next to the vanilla arrow and bow
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> {
			output.insertAfter(Items.ARROW, PRISMARINE_ARROW);
			output.insertAfter(Items.BOW, PRISMARINE_BOW);
		});
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(output -> output.insertAfter(Items.ARROW, PRISMARINE_ARROW));

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> onEntityLoad(entity));
		ServerTickEvents.END_SERVER_TICK.register(server -> checkArrows());
	}

	public static void clear() {
		IN_FLIGHT.clear();
		LAST_POSITION.clear();
		shootingFromBow = false;
		doubling = false;
	}

	/** True for an arrow that the Prismarine Bow shot (the damage mixin asks this of the entity that did the hitting). */
	public static boolean firedFromPrismarineBow(Entity entity) {
		return entity != null && entity.getTags().contains(BOW_ARROW_TAG);
	}

	// ------------------------------------------------------------------ the arrow entity

	/** A new arrow in the world (shot, dispensed, or loaded from a saved chunk): mark it, slow it, and start watching it. */
	private static void onEntityLoad(Entity entity) {
		if (PRISMARINE_ARROW == null || entity.getType() != EntityType.ARROW) {
			return;
		}

		if (!entity.getTags().contains(ARROW_TAG)) {
			// the first time this arrow is seen: is it a Prismarine Arrow?
			if (!carriesPrismarineArrow(entity)) {
				return;
			}

			entity.addTag(ARROW_TAG);

			if (shootingFromBow) {
				entity.addTag(BOW_ARROW_TAG);
			} else {
				// a vanilla bow, a crossbow or a dispenser: a shorter flight
				entity.setDeltaMovement(entity.getDeltaMovement().scale(ARROW_RANGE_FACTOR));
				markForSync(entity);
			}
		}

		if (!IN_FLIGHT.contains(entity)) {
			IN_FLIGHT.add(entity);
		}
	}

	/** Every tick: a Prismarine Arrow that has stopped in a block breaks. */
	private static void checkArrows() {
		if (IN_FLIGHT.isEmpty()) {
			return;
		}

		for (Iterator<Entity> it = IN_FLIGHT.iterator(); it.hasNext(); ) {
			Entity arrow = it.next();

			if (arrow.isRemoved()) {
				LAST_POSITION.remove(arrow);
				it.remove();
				continue;
			}

			if (!(arrow.level() instanceof ServerLevel level)) {
				continue;
			}

			if (hasStopped(arrow)) {
				shatter(level, arrow);
				LAST_POSITION.remove(arrow);
				it.remove();
			}
		}
	}

	/** Whether the arrow has landed in a block: the arrow's own flag when it can be read, otherwise "it did not move for a tick". */
	private static boolean hasStopped(Entity arrow) {
		Boolean inGround = readInGround(arrow);

		if (inGround != null) {
			return inGround;
		}

		Vec3 now = arrow.position();
		Vec3 before = LAST_POSITION.put(arrow, now);
		return before != null && arrow.tickCount > 2 && before.distanceToSqr(now) < 1.0E-8;
	}

	private static void shatter(ServerLevel level, Entity arrow) {
		double x = arrow.getX();
		double y = arrow.getY();
		double z = arrow.getZ();
		level.sendParticles(ParticleTypes.CLOUD, x, y, z, 6, 0.1, 0.1, 0.1, 0.02);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(java.util.Locale.ROOT,
				"playsound minecraft:block.glass.break block @a %.1f %.1f %.1f 0.6 1.6", x, y, z));
		arrow.discard();
	}

	// ------------------------------------------------------------------ reflection helpers (field types, not names)

	private static boolean carriesPrismarineArrow(Entity arrow) {
		if (stackFields == null) {
			List<Field> found = new ArrayList<>();

			for (Class<?> type = arrow.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
				for (Field field : type.getDeclaredFields()) {
					if (ItemStack.class.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) {
						try {
							field.setAccessible(true);
							found.add(field);
						} catch (RuntimeException e) {
							SkyIslands.LOGGER.warn("Sky Islands: cannot read {}: {}", field, e.toString());
						}
					}
				}
			}

			stackFields = found;
		}

		for (Field field : stackFields) {
			try {
				if (field.get(arrow) instanceof ItemStack stack && stack.is(PRISMARINE_ARROW)) {
					return true;
				}
			} catch (IllegalAccessException | RuntimeException e) {
				// try the next field
			}
		}

		return false;
	}

	private static Boolean readInGround(Entity arrow) {
		if (!inGroundSearched) {
			inGroundSearched = true;

			for (Class<?> type = arrow.getClass(); type != null && type != Object.class && inGroundField == null; type = type.getSuperclass()) {
				try {
					Field field = type.getDeclaredField("inGround");

					if (field.getType() == boolean.class) {
						field.setAccessible(true);
						inGroundField = field;
					}
				} catch (ReflectiveOperationException | RuntimeException e) {
					// not in this class
				}
			}

			if (inGroundField == null) {
				SkyIslands.LOGGER.info("Sky Islands: the arrow's \"inGround\" flag was not found, Prismarine Arrows are caught by their stopping instead");
			}
		}

		if (inGroundField == null) {
			return null;
		}

		try {
			return inGroundField.getBoolean(arrow);
		} catch (IllegalAccessException | RuntimeException e) {
			return null;
		}
	}

	/** Asks the server to send the arrow's changed speed to the players, so they do not see it fly at the old speed. */
	private static void markForSync(Entity entity) {
		if (!syncSearched) {
			syncSearched = true;

			for (String name : new String[] { "needsSync", "hurtMarked" }) {
				for (Class<?> type = entity.getClass(); type != null && type != Object.class && syncField == null; type = type.getSuperclass()) {
					try {
						Field field = type.getDeclaredField(name);

						if (field.getType() == boolean.class) {
							field.setAccessible(true);
							syncField = field;
						}
					} catch (ReflectiveOperationException | RuntimeException e) {
						// not in this class
					}
				}
			}
		}

		if (syncField != null) {
			try {
				syncField.setBoolean(entity, true);
			} catch (IllegalAccessException | RuntimeException e) {
				// the arrow just corrects itself a moment later
			}
		}
	}

	// ------------------------------------------------------------------ the bow

	/** A bow that takes only Prismarine Arrows and draws twice as slowly. */
	static final class PrismarineBowItem extends BowItem {
		PrismarineBowItem(Item.Properties properties) {
			super(properties);
		}

		/**
		 * The vanilla bow works the draw out as (use duration - time left). Telling it the time left was shorter than it
		 * really was, by half of the time drawn, makes everything it does (the power, the full-power critical hit) take
		 * {@link #DRAW_TIME_MULTIPLIER} times as long. While it runs, the arrow that comes out is marked as the bow's.
		 */
		@Override
		public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int timeLeft) {
			// a creative player with no Prismarine Arrow would get a plain arrow from the game: not from this bow
			if (user instanceof Player player && !player.getProjectile(stack).is(PRISMARINE_ARROW)) {
				return false;
			}

			int full = getUseDuration(stack, user);
			int drawn = (full - timeLeft) / DRAW_TIME_MULTIPLIER;
			shootingFromBow = true;

			try {
				return super.releaseUsing(stack, level, user, full - drawn);
			} finally {
				shootingFromBow = false;
			}
		}

		// The ammunition: only Prismarine Arrows. No @Override on purpose: this version's method may or may not take the
		// weapon, and either form is covered (a form the game never calls is simply unused).
		public Predicate<ItemStack> getAllSupportedProjectiles() {
			return PRISMARINE_ARROW_ONLY;
		}

		public Predicate<ItemStack> getAllSupportedProjectiles(ItemStack weapon) {
			return PRISMARINE_ARROW_ONLY;
		}

		public Predicate<ItemStack> getSupportedHeldProjectiles() {
			return PRISMARINE_ARROW_ONLY;
		}

		public Predicate<ItemStack> getSupportedHeldProjectiles(ItemStack weapon) {
			return PRISMARINE_ARROW_ONLY;
		}
	}
}
