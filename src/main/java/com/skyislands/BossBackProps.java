package com.skyislands;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Sky Illusioner's bow and Wind Spear (crossed on its back) and its diamond axe (on its right side), shown as models
 * while the boss is not holding them. As soon as the boss takes one of them in its hand to attack, that one disappears
 * from its body; it comes back once the hand holds something else. While the boss is invisible (its mirror image spell)
 * none is shown: the game draws the images of the boss only on the client, so a weapon floating where the real boss
 * stands would give away which one it is.
 *
 * <p>Each model is an item display entity (like the flying spears, see {@link SpearVisual}) that is seated on the boss as a
 * passenger, so the game keeps it exactly where the boss is, frame by frame, however the boss moves. Where it sits and how
 * it is turned with the body is set every tick through the display's transformation, which glides to it. The weapons are
 * always there (and only shrunk to nothing while hidden), so hiding and showing is instant and cheap.
 *
 * <p>The numbers in {@link Kind} are the place to adjust how the weapons sit. Offsets are in the boss's own frame: x
 * sideways (negative is its right), y up from its feet, z forward (so a negative z is behind it).
 */
public final class BossBackProps {
	/** The boss's name (what a client sees on it, so it can tell the boss from any other Illusioner). */
	public static final String BOSS_NAME = "Sky Illusioner";

	public enum Kind {
		/** Behind the body (a little nearer than the spear, so the spear crosses over it); turned about its own face. */
		BOW("{id:\"minecraft:bow\",count:1}", 0.0, 1.15, -0.20, 0.0, -70.0, 0.85),
		/** Behind the body, tip leaning up and to one side (see SPEAR_LEAN below). */
		SPEAR("{id:\"minecraft:stick\",count:1,components:{\"minecraft:item_model\":\"sky_islands:diamond_windspear_projectile\"}}",
				0.0, 1.15, -0.27, 0.0, Math.toDegrees(Math.atan2(0.94, -0.34) - Math.PI / 4.0), 1.15),
		/** Hanging on its right side (the flat face turned outward), head down and toward the back, handle up toward the front. */
		AXE("{id:\"minecraft:diamond_axe\",count:1}", -0.30, 0.95, -0.02, 90.0, 195.0, 0.8);

		final String item;
		/** Sideways and forward offsets, and the height above the feet (blocks). */
		public final double side;
		public final double height;
		public final double back;
		/** How the flat picture is turned: about the vertical axis (degrees, 90 = its face looks to the boss's right), then about its own face. */
		public final double faceYaw;
		public final double roll;
		/** Size: a 1.0 sprite is one block across. */
		public final double scale;

		Kind(String item, double side, double height, double back, double faceYaw, double roll, double scale) {
			this.item = item;
			this.side = side;
			this.height = height;
			this.back = back;
			this.faceYaw = faceYaw;
			this.roll = roll;
			this.scale = scale;
		}
	}

	/** Raise (+) or lower (-) every picture by this many blocks, to correct how high the game seats them on the boss. */
	private static final double SEAT_FINE_TUNE = 0.0;

	/** A picture is placed from this many ticks after it was made (by then the game has seated it and its position can be read). */
	private static final int SETTLE_TICKS = 3;

	private static final class Prop {
		final UUID id;
		int age;
		boolean ready;
		/** Seated on the boss (the game moves it); otherwise it is teleported to the boss every tick. */
		boolean mounted;
		/** How far above the boss's feet the game puts a seated picture (the boss's passenger point), taken away again in the placement. */
		double seatHeight;

		Prop(UUID id) {
			this.id = id;
		}
	}

	private static final class Props {
		final Prop[] props = new Prop[Kind.values().length];
	}

	private static final Map<UUID, Props> PROPS = new HashMap<>();
	private static Field bodyYawField;
	private static boolean bodyYawLookedUp;

	private BossBackProps() { }

	static void clear() {
		PROPS.clear();
	}

	/** Takes the pictures away (the boss died, was removed or unloaded). */
	static void remove(ServerLevel level, UUID mobId) {
		Props props = PROPS.remove(mobId);

		if (props == null) {
			return;
		}

		for (Prop prop : props.props) {
			if (prop != null) {
				SpearVisual.remove(level, prop.id);
			}
		}
	}

	/** Once per tick for each boss: shows the weapons that are not in its hand (and not while it is invisible), hides the others. */
	static void tick(ServerLevel level, Mob mob) {
		Props props = PROPS.computeIfAbsent(mob.getUUID(), id -> new Props());
		ItemStack hand = mob.getMainHandItem();
		boolean hidden = mob.isInvisible();
		// The Illusioner only draws the weapon in its hand while it is aggressive (locked onto a target); otherwise its hands are
		// together and the weapon is not to be seen, so it counts as not in hand and shows on the body instead.
		boolean drawn = mob.isAggressive();
		// a picture that vanished (its chunk was unloaded and reloaded, or something killed it) is made again
		boolean check = mob.tickCount % 20 == 0;
		float yaw = bodyYaw(mob);

		for (Kind kind : Kind.values()) {
			props.props[kind.ordinal()] = update(level, mob, props.props[kind.ordinal()], kind, !hidden && !inHand(kind, hand, drawn), yaw, check);
		}
	}

	private static Prop update(ServerLevel level, Mob mob, Prop prop, Kind kind, boolean show, float yaw, boolean check) {
		if (prop != null && check && level.getEntity(prop.id) == null) {
			SpearVisual.remove(level, prop.id);
			prop = null;
		}

		if (prop == null) {
			UUID id = SpearVisual.spawnProp(level, kind.item, mob.getX(), mob.getY(), mob.getZ());
			SpearVisual.mountProp(level, id, mob.getUUID());
			return new Prop(id);
		}

		prop.age++;

		if (!prop.ready) {
			if (prop.age < SETTLE_TICKS) {
				return prop;
			}

			// The game seats a picture at the boss's passenger point, which is not on its feet: that height has to be taken away
			// again. Read it from where the picture is; if that still says "on the feet" (the position on the server is not always
			// brought up to date for a passenger) the passenger point is at the top of the boss, so its height is used. If the
			// picture could not be seated at all, it is teleported to the boss instead.
			Entity display = level.getEntity(prop.id);

			if (display == null) {
				return prop;
			}

			prop.mounted = display.isPassenger();

			if (prop.mounted) {
				double measured = display.getY() - mob.getY();
				prop.seatHeight = measured > 0.1 ? measured : mob.getBbHeight();
			} else {
				prop.seatHeight = 0.0;
			}

			prop.ready = true;
		}

		if (!prop.mounted) {
			SpearVisual.moveProp(level, prop.id, mob.getX(), mob.getY(), mob.getZ());
		}

		double height = kind.height - prop.seatHeight + (prop.mounted ? SEAT_FINE_TUNE : 0.0);
		SpearVisual.shapeProp(level, prop.id, transformation(yaw, kind, height, show ? kind.scale : 0.0));
		return prop;
	}

	/**
	 * The transformation (NBT) that puts a picture where the kind says (relative to the boss's body) and turns it as the kind
	 * says, and then with the boss's body: the turn of the body is applied to both the picture and where it sits.
	 */
	private static String transformation(double bodyYawDegrees, Kind kind, double height, double scale) {
		double theta = -Math.toRadians(bodyYawDegrees);
		// where it sits: (side, back) turned with the body (a turn about the vertical axis by theta)
		double x = kind.side * Math.cos(theta) + kind.back * Math.sin(theta);
		double z = -kind.side * Math.sin(theta) + kind.back * Math.cos(theta);

		// quaternion of (turn about the vertical axis by theta + faceYaw) after (turn about the picture's own face by roll)
		double yawHalf = (theta + Math.toRadians(kind.faceYaw)) / 2.0;
		double rollHalf = Math.toRadians(kind.roll) / 2.0;
		double sy = Math.sin(yawHalf);
		double cy = Math.cos(yawHalf);
		double sz = Math.sin(rollHalf);
		double cz = Math.cos(rollHalf);

		return String.format(Locale.ROOT, "{translation:[%.4ff,%.4ff,%.4ff],left_rotation:[%.5ff,%.5ff,%.5ff,%.5ff],scale:[%.3ff,%.3ff,%.3ff]}",
				x, height, z, sy * sz, sy * cz, cy * sz, cy * cz, scale, scale, scale);
	}

	/**
	 * True while the boss is showing this weapon in its hand (so it is not on its body): the weapon must be in the main hand and
	 * the boss must be drawing it. The clients use this too, for the images of the boss they draw themselves.
	 */
	public static boolean inHand(Kind kind, ItemStack hand, boolean drawn) {
		return drawn && switch (kind) {
			case BOW -> hand.is(Items.BOW);
			case SPEAR -> isSpear(hand);
			case AXE -> hand.is(Items.DIAMOND_AXE);
		};
	}

	/** Any spear (the Wind Spear it throws, a vanilla one) counts as the boss's spear. */
	private static boolean isSpear(ItemStack stack) {
		return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("spear");
	}

	/** The way the body faces (it trails the head a little, which is what the back follows). Falls back to the head's direction. */
	private static float bodyYaw(Mob mob) {
		if (!bodyYawLookedUp) {
			bodyYawLookedUp = true;

			try {
				bodyYawField = LivingEntity.class.getField("yBodyRot");
			} catch (ReflectiveOperationException | RuntimeException e) {
				bodyYawField = null;
			}
		}

		if (bodyYawField != null) {
			try {
				return bodyYawField.getFloat(mob);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				bodyYawField = null;
			}
		}

		return mob.getYRot();
	}
}
