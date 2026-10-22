package com.skyislands.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import com.skyislands.SkyIslands;

/**
 * Client-side music logic (replaces the old datapack functions):
 *
 * <ul>
 *   <li><b>Launch song</b> (Skyborn): starts when a geyser launches you (the geyser makes your gravity negative), and
 *       fades out when you land (or touch water).</li>
 *   <li><b>Sky tracks</b>: while you are in the overworld above the "enter" height a random track plays (never the same
 *       one twice in a row, with a gap between tracks). Dropping below the "exit" height fades the track out.</li>
 *   <li><b>Boss music</b> (Battle amongst the Clouds): plays, looping, while a Sky Illusioner is nearby (the boss is spawned
 *       when a player wakes it, so its presence is the fight). Everything else fades out quickly as it fades in; when the boss
 *       is dead (or gone) it fades out and normal music can play again.</li>
 *   <li>Vanilla music is silenced while any of these is playing, and sky tracks never overlap the launch song.</li>
 * </ul>
 */
public final class MusicController {
	private static final Identifier LAUNCH_SONG = SkyIslands.id("music.skyborn");
	private static final List<Identifier> SKY_TRACKS = List.of(
			SkyIslands.id("music.swaying_islands"),
			SkyIslands.id("music.an_islands_peace"),
			SkyIslands.id("music.breathtaking_view"),
			SkyIslands.id("music.islands_sorrow"),
			SkyIslands.id("music.singing_islands"));
	private static final Identifier BOSS_SONG = SkyIslands.id("music.battle_amongst_the_clouds");
	/** Must match the name ArenaBoss gives the boss. */
	private static final String BOSS_NAME = "Sky Illusioner";
	private static final double BOSS_RANGE = 64.0;
	private static final int BOSS_SCAN_INTERVAL = 5;

	private static final int FAST_FADE = 20;       // sky track fading out because a launch started
	private static final int START_GRACE = 20;     // ticks before we trust isActive() after starting a sound
	private static final int LANDING_GRACE = 60;   // ticks after launch before landing can end the song

	private static FadingSound launch;
	private static boolean launchFading;
	private static boolean launchArmed = true;
	private static int launchTicks;

	private static FadingSound boss;
	private static boolean bossFading;
	private static boolean bossPresent;
	private static int bossTicks;
	private static int bossScan;

	private static FadingSound sky;
	private static boolean skyFading;
	private static boolean inSkyZone;
	private static int skyTicks;
	private static int skyGap;
	private static int lastTrack = -1;

	private static int muteTimer;
	private static boolean disabled;

	private MusicController() { }

	public static void tick(Minecraft mc) {
		if (disabled || !ClientConfig.musicEnabled) {
			return;
		}

		try {
			doTick(mc);
		} catch (Throwable t) {
			disabled = true;
			SkyIslands.LOGGER.error("Sky Islands music controller crashed and was disabled for this session", t);
		}
	}

	private static void doTick(Minecraft mc) {
		VanillaMusic.tick(mc);

		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;

		if (player == null || level == null) {
			reset(mc);
			return;
		}

		var sounds = mc.getSoundManager();
		boolean overworld = Level.OVERWORLD.equals(level.dimension());
		double y = player.getY();

		// ------------------------------------------------------------------ boss music
		if (!overworld) {
			bossPresent = false;
		} else if (++bossScan >= BOSS_SCAN_INTERVAL) {
			bossScan = 0;
			bossPresent = bossNearby(level, player);
		}

		if (boss != null) {
			bossTicks++;

			if (bossTicks > START_GRACE && !sounds.isActive(boss)) {
				boss = null;
				bossFading = false;
			} else if (!bossPresent && !bossFading) {
				boss.fadeTo(0.0F, ClientConfig.bossFadeOutTicks, true);
				bossFading = true;
			} else if (bossPresent && bossFading) {
				boss.fadeTo(1.0F, ClientConfig.bossFadeInTicks, false); // the boss is still around: bring it back
				bossFading = false;
			}
		} else if (bossPresent) {
			boss = new FadingSound(BOSS_SONG, 0.0F);
			boss.setLoop(true);
			boss.fadeTo(1.0F, ClientConfig.bossFadeInTicks, false);
			sounds.play(boss);
			bossTicks = 0;
			bossFading = false;
			VanillaMusic.fadeOut(mc, ClientConfig.bossFadeInTicks);
		}

		boolean bossBusy = bossPresent || boss != null;

		// ------------------------------------------------------------------ launch song
		boolean thrust = overworld && player.getAttributeValue(Attributes.GRAVITY) < 0.0;

		if (launch != null) {
			launchTicks++;

			if (launchTicks > START_GRACE && !sounds.isActive(launch)) {
				launch = null; // the song ended (or was fully faded out)
			} else if (!launchFading && launchTicks > LANDING_GRACE && (player.onGround() || player.isInWater() || !overworld)) {
				launch.fadeTo(0.0F, ClientConfig.launchFadeOutTicks, true);
				launchFading = true;
			} else if (!launchFading && bossBusy) {
				launch.fadeTo(0.0F, ClientConfig.bossFadeInTicks, true); // a boss fight cuts the launch song
				launchFading = true;
			}
		} else if (thrust && launchArmed && !bossBusy) {
			launch = new FadingSound(LAUNCH_SONG, 0.0F);
			launch.fadeTo(1.0F, ClientConfig.launchFadeInTicks, false);
			sounds.play(launch);
			VanillaMusic.fadeOut(mc, Math.max(40, ClientConfig.launchFadeInTicks));
			launchTicks = 0;
			launchFading = false;
			launchArmed = false;
		}

		if (!thrust && launch == null) {
			launchArmed = true;
		}

		boolean launchBusy = launch != null;

		// ------------------------------------------------------------------ sky tracks
		if (!overworld) {
			inSkyZone = false;
		} else if (!inSkyZone && y >= ClientConfig.skyMusicEnterY) {
			inSkyZone = true;
		} else if (inSkyZone && y < ClientConfig.skyMusicExitY) {
			inSkyZone = false;
		}

		boolean skyWanted = inSkyZone && !launchBusy && !bossBusy;

		if (sky != null) {
			skyTicks++;

			if (skyTicks > START_GRACE && !sounds.isActive(sky)) {
				sky = null;
				skyFading = false;
				skyGap = ClientConfig.skyMusicGapTicks;
			} else if (!skyWanted && !skyFading) {
				sky.fadeTo(0.0F, bossBusy ? ClientConfig.bossFadeInTicks : launchBusy ? FAST_FADE : ClientConfig.skyFadeOutTicks, true);
				skyFading = true;
			} else if (skyWanted && skyFading) {
				sky.fadeTo(1.0F, ClientConfig.skyFadeInTicks, false); // came back up before the fade finished
				skyFading = false;
			}
		} else {
			if (skyGap > 0) {
				skyGap--;
			}

			if (skyWanted && skyGap <= 0) {
				startSkyTrack(mc);
			}
		}

		// ------------------------------------------------------------------ keep vanilla music quiet
		if (launch != null || sky != null || boss != null || bossBusy || inSkyZone) {
			if (++muteTimer >= 10) {
				muteTimer = 0;
				VanillaMusic.silence(mc);
			}
		}
	}

	/** True when a living Sky Illusioner (the arena boss) is loaded close to the player. */
	private static boolean bossNearby(ClientLevel level, LocalPlayer player) {
		return !level.getEntities(player, player.getBoundingBox().inflate(BOSS_RANGE), MusicController::isBoss).isEmpty();
	}

	private static boolean isBoss(Entity entity) {
		if (entity.getType() != EntityType.ILLUSIONER || !entity.isAlive() || entity.isRemoved()) {
			return false;
		}

		var name = entity.getCustomName();
		return name != null && BOSS_NAME.equals(name.getString());
	}

	private static void startSkyTrack(Minecraft mc) {
		int pick = mc.level.getRandom().nextInt(SKY_TRACKS.size());

		if (pick == lastTrack) {
			pick = (pick + 1) % SKY_TRACKS.size();
		}

		lastTrack = pick;
		sky = new FadingSound(SKY_TRACKS.get(pick), 0.0F);
		sky.fadeTo(1.0F, ClientConfig.skyFadeInTicks, false);
		mc.getSoundManager().play(sky);
		skyTicks = 0;
		skyFading = false;
		VanillaMusic.fadeOut(mc, ClientConfig.skyFadeInTicks);
	}

	private static void reset(Minecraft mc) {
		if (launch != null) {
			launch.kill();
		}

		if (sky != null) {
			sky.kill();
		}

		if (boss != null) {
			boss.kill();
		}

		boss = null;
		bossFading = false;
		bossPresent = false;
		bossScan = 0;
		launch = null;
		sky = null;
		launchFading = false;
		skyFading = false;
		launchArmed = true;
		inSkyZone = false;
		skyGap = 0;
	}
}
