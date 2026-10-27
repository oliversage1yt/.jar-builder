package com.skyislands;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
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
 * tick: an arrow that has stopped in the ground is removed with prismarine block-break particles and the glass break sound.</li>
 * <li>It is drawn as the Prismarine Arrow item, spinning along its flight (like the Wind Spears), not as the plain arrow model:
 * the arrow entity is named {@link #HIDDEN_NAME}, which the client takes as "do not draw this one" (PrismarineArrowRenderMixin), and a
 * server-side item display follows it each tick (see {@link SpearVisual#spawnItem}). The picture is made at the very start of
 * the tick the arrow appears in ({@link #beforeTick}), so it is sent to the players together with the arrow and shows at once.</li>
 * <li>It does not slow down in water, like a trident (see {@link #keepSpeedInWater}): vanilla arrows keep only 60% of their
 * speed per tick under water, a Prismarine Arrow keeps 99%.</li>
 * <li>Right click with it throws it (see {@link ThrownPrismarineArrows}): short range, no cooldown.</li>
 * </ul>
 *
 * <p><b>Bow</b>: a {@link BowItem} that only takes Prismarine Arrows, with
 * <ul>
 * <li>twice the damage ({@link #BOW_DAMAGE_MULTIPLIER}): every arrow it shoots is marked, and
 * {@code PrismarineBowDamageMixin} doubles the damage such an arrow deals (Power, Impaling and a critical hit included);</li>
 * <li>half the durability of a vanilla bow ({@link #BOW_DURABILITY} instead of 384): the same thing as breaking twice as fast;</li>
 * <li>a draw that takes {@link #DRAW_TIME_MULTIPLIER} times as long (26 ticks to full power instead of 20), see
 * {@link PrismarineBowItem#releaseUsing} (and the pull textures in items/prismarine_bow.json, which use half the vanilla scale);</li>
 * <li>In creative mode it fires a Prismarine Arrow even with none in the inventory, as the vanilla bow does with plain arrows.</li>
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
	public static final float BOW_DAMAGE_MULTIPLIER = 2.0F;
	/** The vanilla bow has 384 uses; half as many is the same as wearing out twice as fast, whatever Unbreaking does. */
	private static final int BOW_DURABILITY = 192;
	/** The Prismarine Bow takes this many times as long to draw (20 ticks to full power in vanilla, so 26 here). */
	private static final double DRAW_TIME_MULTIPLIER = 1.3;

	/** Scoreboard tag on every Prismarine Arrow entity (saved with the arrow, so it survives a reload). */
	static final String ARROW_TAG = "sky_islands_prismarine_arrow";
	/** Scoreboard tag on an arrow that was shot from the Prismarine Bow (its damage is doubled). */
	static final String BOW_ARROW_TAG = "sky_islands_prismarine_bow_arrow";

	/**
	 * The name every Prismarine Arrow entity carries. The name is sent to the players, which is how the client knows to leave
	 * the plain arrow model undrawn (the arrow is shown as a spinning item picture instead, see {@link SpearVisual}).
	 */
	public static final String HIDDEN_NAME = "sky_islands:prismarine_arrow";
	/** How big the spinning arrow picture is (the arrow sprite is one block across; its arrow is about 1.2 blocks long at 1.0). */
	static final double VISUAL_SCALE = 0.7;
	/**
	 * Where the arrow sprite's tip points as an item display draws it: the sprite has its tip at the upper right, and an item
	 * display draws an item mirrored left to right, so (-1, 1).
	 */
	static final double TIP_X = -1.0;
	static final double TIP_Y = 1.0;
	/** The item the spinning picture shows. */
	static final String VISUAL_ITEM = "sky_islands:prismarine_arrow";

	/** A trident keeps 99% of its speed per tick under water (a vanilla arrow only 60%); a Prismarine Arrow does the same as a trident. */
	private static final double WATER_INERTIA = 0.99;
	/** What an arrow loses of its upward speed every tick (vanilla's arrow gravity). */
	private static final double ARROW_GRAVITY = 0.05;
	/** How fast an arrow a mob (a drowned) shoots leaves the bow, in blocks per tick (a skeleton's arrow is 1.6). */
	private static final double MOB_ARROW_SPEED = 1.6;

	public static Item PRISMARINE_ARROW;
	public static Item PRISMARINE_BOW;

	/** True while the Prismarine Bow is letting an arrow go (the arrow enters the world inside that call). */
	private static boolean shootingFromBow = false;
	/** True while a mob (a drowned with a Prismarine Bow) is shooting: the arrow is a Prismarine Arrow, but not doubled and not slowed. */
	private static boolean shootingFromMob = false;
	/** Used by the damage mixin: true while the doubled hit is being dealt, so it is not doubled again. */
	public static boolean doubling = false;

	/** Prismarine Arrows that are in the world, checked every tick for having hit a block. */
	private static final List<Entity> IN_FLIGHT = new ArrayList<>();
	/** Where each of them was a tick ago (only used when the arrow's "in ground" flag cannot be read). */
	private static final Map<Entity, Vec3> LAST_POSITION = new IdentityHashMap<>();
	/** The spinning item picture that stands in for each arrow's plain model. */
	private static final Map<Entity, UUID> VISUALS = new IdentityHashMap<>();
	/** How fast each arrow was going when the tick began (what is needed to take the water's drag back off, see keepSpeedInWater). */
	private static final Map<Entity, Vec3> PRE_VELOCITY = new IdentityHashMap<>();

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
		PRISMARINE_ARROW = Registry.register(BuiltInRegistries.ITEM, arrowId, new PrismarineArrowItem(arrowProperties));

		Identifier bowId = SkyIslands.id("prismarine_bow");
		Item.Properties bowProperties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, bowId)).durability(BOW_DURABILITY).enchantable(1);
		bowProperties.component(DataComponents.ITEM_MODEL, bowId);
		bowProperties.component(DataComponents.ITEM_NAME, Component.translatable("item.sky_islands.prismarine_bow"));
		PRISMARINE_BOW = Registry.register(BuiltInRegistries.ITEM, bowId, new PrismarineBowItem(bowProperties));

		// A dispenser only fires what it has a behaviour for, otherwise it spits the item out. This is the same registration the
		// vanilla arrow gets; the arrow it makes is picked up by onEntityLoad like any other (so it is marked, drawn as the item,
		// slowed to ARROW_RANGE_FACTOR, and shatters where it lands).
		DispenserBlock.registerProjectileBehavior(PRISMARINE_ARROW);

		// creative tabs: next to the vanilla arrow and bow
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> {
			output.insertAfter(Items.ARROW, PRISMARINE_ARROW);
			output.insertAfter(Items.BOW, PRISMARINE_BOW);
		});
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(output -> output.insertAfter(Items.ARROW, PRISMARINE_ARROW));

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> onEntityLoad(entity));
		// the start of the tick comes before the players are sent what is new in the world, the end of it does not
		ServerTickEvents.START_SERVER_TICK.register(server -> beforeTick());
		ServerTickEvents.END_SERVER_TICK.register(server -> checkArrows());
	}

	public static void clear() {
		IN_FLIGHT.clear();
		LAST_POSITION.clear();
		VISUALS.clear();
		PRE_VELOCITY.clear();
		shootingFromBow = false;
		shootingFromMob = false;
		doubling = false;
	}

	/** True for an arrow that the Prismarine Bow shot (the damage mixin asks this of the entity that did the hitting). */
	public static boolean firedFromPrismarineBow(Entity entity) {
		return entity != null && ArenaBoss.tagsOf(entity).contains(BOW_ARROW_TAG);
	}

	/** True for the entity whose plain arrow model must not be drawn (asked by the client, see PrismarineArrowRenderMixin). */
	public static boolean isHiddenArrow(Entity entity) {
		if (entity == null || !entity.hasCustomName()) {
			return false;
		}

		var name = entity.getCustomName();
		return name != null && HIDDEN_NAME.equals(name.getString());
	}

	// ------------------------------------------------------------------ the arrow entity

	/** An arrow entity, told by its registry name (the entity type constants are not needed). */
	private static boolean isArrow(Entity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("arrow");
	}

	/** A new arrow in the world (shot, dispensed, or loaded from a saved chunk): mark it, slow it, and start watching it. */
	private static void onEntityLoad(Entity entity) {
		if (PRISMARINE_ARROW == null || !isArrow(entity)) {
			return;
		}

		if (!ArenaBoss.tagsOf(entity).contains(ARROW_TAG)) {
			// the first time this arrow is seen: is it a Prismarine Arrow?
			if (!carriesPrismarineArrow(entity)) {
				// A creative player with no arrows gets a plain arrow from the game's own bow code, as with the vanilla bow.
				// From the Prismarine Bow it is made into a Prismarine Arrow.
				if (!(shootingFromBow || shootingFromMob) || !makePrismarine(entity)) {
					return;
				}
			}

			entity.addTag(ARROW_TAG);
			entity.setCustomName(Component.literal(HIDDEN_NAME));

			if (shootingFromBow) {
				entity.addTag(BOW_ARROW_TAG);
			} else if (!shootingFromMob) {
				// a vanilla bow, a crossbow or a dispenser: a shorter flight (a mob's arrow is already shot at the speed it should have)
				entity.setDeltaMovement(entity.getDeltaMovement().scale(ARROW_RANGE_FACTOR));
				markForSync(entity);
			}
		}

		if (!IN_FLIGHT.contains(entity)) {
			IN_FLIGHT.add(entity);
		}
	}

	/** Every tick: a Prismarine Arrow that has stopped in a block breaks, and the spinning picture of each flying one follows it. */
	private static void checkArrows() {
		if (IN_FLIGHT.isEmpty()) {
			return;
		}

		for (Iterator<Entity> it = IN_FLIGHT.iterator(); it.hasNext(); ) {
			Entity arrow = it.next();

			if (arrow.isRemoved()) {
				if (arrow.level() instanceof ServerLevel level) {
					removeVisual(level, arrow);
				}

				VISUALS.remove(arrow);
				LAST_POSITION.remove(arrow);
				PRE_VELOCITY.remove(arrow);
				it.remove();
				continue;
			}

			if (!(arrow.level() instanceof ServerLevel level)) {
				continue;
			}

			if (hasStopped(arrow)) {
				shatter(level, arrow);
				removeVisual(level, arrow);
				LAST_POSITION.remove(arrow);
				PRE_VELOCITY.remove(arrow);
				it.remove();
			} else {
				keepSpeedInWater(level, arrow);

				if (VISUALS.containsKey(arrow)) {
					showVisual(level, arrow);
				} else {
					// an arrow made during this tick (a dispenser's, a mob's): its picture starts at the arrow, as in beforeTick
					startVisual(level, arrow);
				}
			}
		}
	}

	/**
	 * The start of every tick. An arrow that has no picture yet gets one right where it is: the players are sent what is new in
	 * the world while the tick runs, so a picture made now arrives together with the arrow (made at the end of the tick, as it
	 * used to be, it came a whole tick later and showed up several blocks ahead of the bow). Also remembers how fast each arrow
	 * is going, which {@link #keepSpeedInWater} needs once the tick is over.
	 */
	private static void beforeTick() {
		if (IN_FLIGHT.isEmpty()) {
			return;
		}

		for (Entity arrow : IN_FLIGHT) {
			if (arrow.isRemoved() || !(arrow.level() instanceof ServerLevel level)) {
				continue;
			}

			PRE_VELOCITY.put(arrow, arrow.getDeltaMovement());

			if (!VISUALS.containsKey(arrow)) {
				startVisual(level, arrow);
			}
		}
	}

	/** Makes the arrow's spinning picture at the arrow itself, pointing the way it is going. */
	private static void startVisual(ServerLevel level, Entity arrow) {
		Vec3 velocity = arrow.getDeltaMovement();
		double length = velocity.length();

		if (length < 1.0E-4) {
			return;
		}

		VISUALS.put(arrow, SpearVisual.spawnItem(level, VISUAL_ITEM, VISUAL_SCALE, TIP_X, TIP_Y,
				arrow.getX(), arrow.getY(), arrow.getZ(), velocity.x / length, velocity.y / length, velocity.z / length));
	}

	/**
	 * A Prismarine Arrow ignores the water's resistance, like a trident. The vanilla arrow entity multiplies its speed by 0.6
	 * every tick it is in water, and its drag cannot be changed from here, so after the tick the speed it had at the start is
	 * put back, slowed by a trident's 0.99 instead and with the arrow's gravity taken off. (Skipped if the arrow hit something
	 * and bounced off, or has stopped in a block: then its speed no longer points the way it was going.) The speed is not sent to
	 * the players: the arrow is only drawn from the server's picture of it, which follows this speed.
	 */
	private static void keepSpeedInWater(ServerLevel level, Entity arrow) {
		if (!arrow.isInWater()) {
			return;
		}

		level.sendParticles(ParticleTypes.BUBBLE, arrow.getX(), arrow.getY(), arrow.getZ(), 2, 0.05, 0.05, 0.05, 0.01);

		Vec3 before = PRE_VELOCITY.get(arrow);
		Vec3 now = arrow.getDeltaMovement();

		if (before == null || before.lengthSqr() < 1.0E-6 || now.dot(before) <= 0.0) {
			return;
		}

		arrow.setDeltaMovement(before.x * WATER_INERTIA, before.y * WATER_INERTIA - ARROW_GRAVITY, before.z * WATER_INERTIA);
	}

	/**
	 * Puts the spinning arrow picture where the arrow will be a tick from now: the picture glides there over that tick, so it
	 * arrives together with the real (undrawn) arrow instead of a tick behind it.
	 */
	private static void showVisual(ServerLevel level, Entity arrow) {
		Vec3 velocity = arrow.getDeltaMovement();
		double length = velocity.length();

		if (length < 1.0E-4) {
			return;
		}

		double dx = velocity.x / length;
		double dy = velocity.y / length;
		double dz = velocity.z / length;
		Vec3 ahead = arrow.position().add(velocity);
		UUID id = VISUALS.get(arrow);

		if (id == null) {
			VISUALS.put(arrow, SpearVisual.spawnItem(level, VISUAL_ITEM, VISUAL_SCALE, TIP_X, TIP_Y, ahead.x, ahead.y, ahead.z, dx, dy, dz));
		} else {
			SpearVisual.moveItem(level, id, ahead.x, ahead.y, ahead.z, dx, dy, dz, arrow.tickCount, TIP_X, TIP_Y);
		}
	}

	private static void removeVisual(ServerLevel level, Entity arrow) {
		UUID id = VISUALS.remove(arrow);

		if (id != null) {
			SpearVisual.remove(level, id);
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

	/** The arrow breaks: prismarine block-break particles and the glass break sound. */
	private static void shatter(ServerLevel level, Entity arrow) {
		shatterAt(level, arrow.getX(), arrow.getY(), arrow.getZ());
		arrow.discard();
	}

	/** The break effect alone (a thrown Prismarine Arrow, see {@link ThrownPrismarineArrows}, has no arrow entity to discard). */
	static void shatterAt(ServerLevel level, double x, double y, double z) {
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PRISMARINE.defaultBlockState()), x, y, z, 28, 0.12, 0.12, 0.12, 0.08);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:block.glass.break block @a %.1f %.1f %.1f 0.6 1.6", x, y, z));
	}

	/**
	 * A mob (a drowned holding a Prismarine Bow) shoots a Prismarine Arrow at a target: aimed like a skeleton's (a little high,
	 * to make up for the fall, a little off on the lower difficulties). It is the same arrow as any other Prismarine Arrow (it
	 * shatters, is drawn as the spinning item, ignores the water), but it is not doubled and not slowed, and cannot be picked up.
	 */
	static void shootFromMob(ServerLevel level, Mob mob, LivingEntity target) {
		double sx = mob.getX();
		double sy = mob.getEyeY() - 0.1;
		double sz = mob.getZ();
		double dx = target.getX() - sx;
		double dy = target.getY() + target.getBbHeight() * 0.35 - sy;
		double dz = target.getZ() - sz;
		dy += Math.sqrt(dx * dx + dz * dz) * 0.2;

		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (length < 1.0E-6) {
			return;
		}

		// a skeleton's spread: 14 - 4 x difficulty (10 on Easy, 6 on Normal, 2 on Hard)
		double spread = 0.0075 * Math.max(2, 14 - 4 * level.getDifficulty().ordinal());
		double ux = dx / length + level.getRandom().nextGaussian() * spread;
		double uy = dy / length + level.getRandom().nextGaussian() * spread;
		double uz = dz / length + level.getRandom().nextGaussian() * spread;
		double norm = Math.sqrt(ux * ux + uy * uy + uz * uz);
		ux /= norm;
		uy /= norm;
		uz /= norm;

		shootingFromMob = true;

		try {
			ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
					"summon minecraft:arrow %.2f %.2f %.2f {Motion:[%.3f,%.3f,%.3f],Owner:%s,pickup:0b}",
					sx + ux * 0.6, sy + uy * 0.6, sz + uz * 0.6, ux * MOB_ARROW_SPEED, uy * MOB_ARROW_SPEED, uz * MOB_ARROW_SPEED,
					ArenaBoss.uuidNbt(mob.getUUID())));
		} finally {
			shootingFromMob = false;
		}

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"playsound minecraft:entity.arrow.shoot hostile @a %.1f %.1f %.1f 1.0 %.2f", sx, sy, sz, 0.9 + level.getRandom().nextDouble() * 0.3));
	}

	/** Turns the plain arrow a creative player's bow produced into a Prismarine Arrow (swaps the stack it gives back when picked up). */
	private static boolean makePrismarine(Entity arrow) {
		if (!carriesArrowStack(arrow)) {
			return false;
		}

		for (Field field : stackFields) {
			try {
				if (field.get(arrow) instanceof ItemStack stack && stack.is(Items.ARROW)) {
					field.set(arrow, new ItemStack(PRISMARINE_ARROW));
					return true;
				}
			} catch (IllegalAccessException | RuntimeException e) {
				// try the next field
			}
		}

		return false;
	}

	// ------------------------------------------------------------------ reflection helpers (field types, not names)

	private static boolean carriesArrowStack(Entity arrow) {
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

		return !stackFields.isEmpty();
	}

	private static boolean carriesPrismarineArrow(Entity arrow) {
		if (!carriesArrowStack(arrow)) {
			return false;
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

	// ------------------------------------------------------------------ the arrow item

	/** The arrow item: it is still an ordinary arrow for the bows, and right click throws it by hand (see {@link ThrownPrismarineArrows}). */
	static final class PrismarineArrowItem extends ArrowItem {
		PrismarineArrowItem(Item.Properties properties) {
			super(properties);
		}

		@Override
		public InteractionResult use(Level level, Player player, InteractionHand hand) {
			if (player.isSpectator()) {
				return InteractionResult.PASS;
			}

			if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
				ThrownPrismarineArrows.throwArrow(serverLevel, serverPlayer, player.getItemInHand(hand));
			}

			// no cooldown on purpose: the player can throw one every time they click
			return InteractionResult.SUCCESS;
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
		 * {@link #DRAW_TIME_MULTIPLIER} times as long. While it runs, the arrow that comes out is marked as the bow's (and, for a
		 * creative player with no Prismarine Arrow, which the game answers with a plain arrow, made into a Prismarine Arrow).
		 */
		@Override
		public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int timeLeft) {
			int full = getUseDuration(stack, user);
			int drawn = (int) Math.floor((full - timeLeft) / DRAW_TIME_MULTIPLIER + 1.0E-6);
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
