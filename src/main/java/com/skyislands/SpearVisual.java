package com.skyislands;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.server.level.ServerLevel;

/**
 * The picture of a flying Wind Spear: an item display entity that follows a spear the mod flies itself, turned to point
 * along its path and spinning about its own length, like a thrown trident.
 *
 * <p>The picture is the spear's flat in-hand sprite (the extruded slab of the "Windspear" model), shown through a plain
 * stick whose item model is replaced (the {@code item_model} component), so nothing here is an item a player can get.
 * In the sprite the spear's tip is at the upper right, so the model's tip direction is (1, 1, 0).
 */
final class SpearVisual {
	/** Every picture carries this tag, so one left behind by a restart can be found and removed. */
	static final String TAG = "sky_islands_spear";

	/** Size of the picture (the in-hand sprite is one block across, so its spear is about 1.4 blocks long at 1.0). */
	static final double SCALE = 1.5;
	/** How far the spear turns about its own length every tick (0 = no spin). */
	static final double SPIN_DEGREES_PER_TICK = 24.0;

	private static final Set<UUID> LIVE = new HashSet<>();

	private SpearVisual() { }

	/** Removes pictures left over from before a restart or a crash; call once at start-up. */
	static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!LIVE.contains(entity.getUUID()) && ArenaBoss.tagsOf(entity).contains(TAG)) {
				entity.discard();
			}
		});
	}

	static void clear() {
		LIVE.clear();
	}

	/** Shows a spear of the given tier ("diamond", "iron", ...) at a position, pointing along a direction (a unit vector). */
	static UUID spawn(ServerLevel level, String tier, double x, double y, double z, double dx, double dy, double dz) {
		UUID id = UUID.randomUUID();
		LIVE.add(id);

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"summon minecraft:item_display %.2f %.2f %.2f {UUID:%s,Tags:[\"%s\"],Rotation:[0f,0f],"
						+ "item:{id:\"minecraft:stick\",count:1,components:{\"minecraft:item_model\":\"sky_islands:%s_windspear_projectile\"}},"
						+ "item_display:\"none\",teleport_duration:1,start_interpolation:0,interpolation_duration:1,"
						+ "transformation:{translation:[0f,0f,0f],left_rotation:%s,scale:[%.2ff,%.2ff,%.2ff],right_rotation:[0f,0f,0f,1f]}}",
				x, y, z, ArenaBoss.uuidNbt(id), TAG, tier, rotation(dx, dy, dz, 0), SCALE, SCALE, SCALE));
		return id;
	}

	/**
	 * Shows a real item (for example "sky_islands:prismarine_arrow") as a flying, spinning picture, the same way as a spear. The
	 * item's flat sprite must have its tip at the upper right (as the arrow's does), because {@link #rotation} turns that
	 * corner to the direction of flight.
	 */
	static UUID spawnItem(ServerLevel level, String itemId, double scale, double x, double y, double z, double dx, double dy, double dz) {
		UUID id = UUID.randomUUID();
		LIVE.add(id);

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"summon minecraft:item_display %.2f %.2f %.2f {UUID:%s,Tags:[\"%s\"],Rotation:[0f,0f],"
						+ "item:{id:\"%s\",count:1},"
						+ "item_display:\"none\",teleport_duration:1,start_interpolation:0,interpolation_duration:1,"
						+ "transformation:{translation:[0f,0f,0f],left_rotation:%s,scale:[%.2ff,%.2ff,%.2ff],right_rotation:[0f,0f,0f,1f]}}",
				x, y, z, ArenaBoss.uuidNbt(id), TAG, itemId, rotation(dx, dy, dz, 0), scale, scale, scale));
		return id;
	}

	/** Moves the picture and turns it to the current direction of flight; {@code age} is how many ticks it has been flying (for the spin). */
	static void move(ServerLevel level, UUID id, double x, double y, double z, double dx, double dy, double dz, int age) {
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (length < 1.0E-6) {
			return;
		}

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f", id, x, y, z));
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"data merge entity %s {start_interpolation:0,interpolation_duration:1,transformation:{left_rotation:%s}}",
				id, rotation(dx / length, dy / length, dz / length, age)));
	}

	/**
	 * Makes a picture that is not a flying spear (a weapon on the boss's back, see {@link BossBackProps}). It is the same kind of
	 * item display, tagged the same way, so one left behind by a restart is removed with the rest. It starts out invisible (scale 0):
	 * {@link #shapeProp} shows it.
	 */
	static UUID spawnProp(ServerLevel level, String itemNbt, double x, double y, double z) {
		UUID id = UUID.randomUUID();
		LIVE.add(id);

		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"summon minecraft:item_display %.3f %.3f %.3f {UUID:%s,Tags:[\"%s\"],Rotation:[0f,0f],item:%s,"
						+ "item_display:\"none\",teleport_duration:1,start_interpolation:0,interpolation_duration:1,"
						+ "transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[0f,0f,0f],right_rotation:[0f,0f,0f,1f]}}",
				x, y, z, ArenaBoss.uuidNbt(id), TAG, itemNbt));
		return id;
	}

	/** Seats a picture on an entity: from then on the game itself keeps it at the entity's position, frame by frame. */
	static void mountProp(ServerLevel level, UUID id, UUID vehicle) {
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT, "ride %s mount %s", id, vehicle));
	}

	/** Sets how a picture is placed and turned, relative to where it is (a transformation as NBT); it glides there in one tick. */
	static void shapeProp(ServerLevel level, UUID id, String transformation) {
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT,
				"data merge entity %s {start_interpolation:0,interpolation_duration:1,transformation:%s}", id, transformation));
	}

	/** Moves a picture that is not seated on anything. */
	static void moveProp(ServerLevel level, UUID id, double x, double y, double z) {
		ArenaBoss.command(level, ArenaBoss.quietSource(level), String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f 0 0", id, x, y, z));
	}

	static void remove(ServerLevel level, UUID id) {
		LIVE.remove(id);
		ArenaBoss.command(level, ArenaBoss.quietSource(level), "kill " + id);
	}

	/**
	 * The rotation (an NBT quaternion list [x, y, z, w]) that turns the sprite's tip, which points along (1, 1, 0), to the
	 * direction of flight d (a unit vector), then spins it about d.
	 */
	static String rotation(double dx, double dy, double dz, int age) {
		double s = Math.sqrt(0.5);
		double ux = s;
		double uy = s;
		double uz = 0.0;

		// Shortest turn from u to d: axis u x d, angle between them.
		double dot = ux * dx + uy * dy + uz * dz;
		double qx;
		double qy;
		double qz;
		double qw;

		if (dot < -0.999999) {
			qx = 0.0;
			qy = 0.0;
			qz = 1.0;
			qw = 0.0;
		} else {
			qx = uy * dz - uz * dy;
			qy = uz * dx - ux * dz;
			qz = ux * dy - uy * dx;
			qw = 1.0 + dot;
			double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
			qx /= norm;
			qy /= norm;
			qz /= norm;
			qw /= norm;
		}

		double half = Math.toRadians(SPIN_DEGREES_PER_TICK * age) / 2.0;
		double sin = Math.sin(half);
		double ax = dx * sin;
		double ay = dy * sin;
		double az = dz * sin;
		double aw = Math.cos(half);

		// (the spin about d) applied after (the turn)
		double rw = aw * qw - (ax * qx + ay * qy + az * qz);
		double rx = aw * qx + qw * ax + (ay * qz - az * qy);
		double ry = aw * qy + qw * ay + (az * qx - ax * qz);
		double rz = aw * qz + qw * az + (ax * qy - ay * qx);

		return String.format(Locale.ROOT, "[%.5ff,%.5ff,%.5ff,%.5ff]", rx, ry, rz, rw);
	}
}
