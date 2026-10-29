package com.skyislands.client;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * A music track whose volume can be faded in or out. It plays in the MUSIC category (so the in-game Music slider
 * controls it), is not positional, and does not loop unless {@link #setLoop} is used.
 */
public class FadingSound extends AbstractSoundInstance implements TickableSoundInstance {
	private final Identifier soundId;
	private boolean stopped;
	private boolean stopAtZero;
	private float target;
	private float step;
	private int fadeTicksLeft;

	public FadingSound(Identifier sound, float startVolume) {
		this(sound, SoundSource.MUSIC, startVolume);
	}

	/** The same, in another category (so another volume slider controls it). */
	public FadingSound(Identifier sound, SoundSource source, float startVolume) {
		super(sound, source, SoundInstance.createUnseededRandom());
		this.soundId = sound;
		this.volume = startVolume;
		this.target = startVolume;
		this.looping = false;
		this.relative = true;
		this.attenuation = SoundInstance.Attenuation.NONE;
	}

	/** Fade the volume to {@code targetVolume} over {@code ticks}; if {@code stopAtZero} and the target is 0 the track ends there. */
	public void fadeTo(float targetVolume, int ticks, boolean stopAtZero) {
		this.target = targetVolume;
		this.stopAtZero = stopAtZero;

		if (ticks <= 0) {
			this.volume = targetVolume;
			this.fadeTicksLeft = 0;
			this.step = 0;
		} else {
			this.fadeTicksLeft = ticks;
			this.step = (targetVolume - this.volume) / ticks;
		}
	}

	public Identifier soundId() {
		return this.soundId;
	}

	/** Make the track repeat until it is stopped (used by the boss music, which must outlast the fight). */
	public void setLoop(boolean loop) {
		this.looping = loop;
	}

	/** End the track immediately (no fade). */
	public void kill() {
		this.stopped = true;
	}

	public float currentVolume() {
		return this.volume;
	}

	@Override
	public void tick() {
		if (this.fadeTicksLeft > 0) {
			this.fadeTicksLeft--;
			this.volume += this.step;

			if (this.fadeTicksLeft == 0) {
				this.volume = this.target;
			}
		}

		if (this.volume < 0.0F) {
			this.volume = 0.0F;
		}

		if (this.stopAtZero && this.fadeTicksLeft == 0 && this.target <= 0.0F) {
			this.stopped = true;
		}
	}

	@Override
	public boolean isStopped() {
		return this.stopped;
	}
}
