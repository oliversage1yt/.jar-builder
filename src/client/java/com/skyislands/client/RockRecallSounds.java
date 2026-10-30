package com.skyislands.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import com.skyislands.RockRecallPayload;
import com.skyislands.SkyIslands;

/**
 * The sounds of a launched fallen rock, for the player who launched it. The server says which stage the rock is in
 * ({@link RockRecallPayload}):
 * <ul>
 * <li>the rock starts to rise: the "perform" sound starts and loops;</li>
 * <li>the rock reaches its peak (or the flight ends some other way): the "perform" sound fades out;</li>
 * <li>the rock starts to fall: the "finish" sound plays once.</li>
 * </ul>
 */
final class RockRecallSounds {
	private static final Identifier PERFORM = SkyIslands.id("rock.recall_perform");
	private static final Identifier FINISH = SkyIslands.id("rock.recall_finish");
	private static final float PERFORM_VOLUME = 1.0F;
	private static final float FINISH_VOLUME = 1.0F;
	/** The looping sound fades out over this many ticks (1 second) at the peak. */
	private static final int PERFORM_FADE_OUT = 20;
	/** A rise is cut short by the server after 1200 ticks; if no message ever arrives the sound fades out after this long. */
	private static final int PERFORM_MAX_TICKS = 1500;
	/** Ticks before isActive() is trusted after a sound was started. */
	private static final int START_GRACE = 20;

	private static FadingSound perform;
	private static boolean fading;
	private static int ticks;

	private RockRecallSounds() { }

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RockRecallPayload.TYPE, (payload, context) -> handle(payload.event()));
	}

	private static void handle(int event) {
		Minecraft mc = Minecraft.getInstance();

		switch (event) {
			case RockRecallPayload.RISE -> startPerform(mc);
			case RockRecallPayload.PEAK, RockRecallPayload.END -> fadePerform();
			case RockRecallPayload.FALL -> {
				fadePerform();
				mc.getSoundManager().play(new FadingSound(FINISH, SoundSource.BLOCKS, FINISH_VOLUME));
			}
			default -> { }
		}
	}

	private static void startPerform(Minecraft mc) {
		if (perform != null) {
			perform.kill();
		}

		perform = new FadingSound(PERFORM, SoundSource.BLOCKS, PERFORM_VOLUME);
		perform.setLoop(true);
		mc.getSoundManager().play(perform);
		ticks = 0;
		fading = false;
	}

	private static void fadePerform() {
		if (perform != null && !fading) {
			perform.fadeTo(0.0F, PERFORM_FADE_OUT, true);
			fading = true;
		}
	}

	static void tick(Minecraft mc) {
		if (perform == null) {
			return;
		}

		if (mc.player == null || mc.level == null) {
			// left the world: nothing may keep looping
			perform.kill();
			perform = null;
			fading = false;
			return;
		}

		ticks++;

		if (!fading && ticks > PERFORM_MAX_TICKS) {
			fadePerform();
		}

		if (perform.isStopped() || (ticks > START_GRACE && !mc.getSoundManager().isActive(perform))) {
			perform = null;
			fading = false;
		}
	}
}
