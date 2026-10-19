package com.skyislands.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;

/**
 * Port of the original Sky Lands "cloud barrier" particle: a huge camera-facing translucent sprite that drifts slowly,
 * fades in and then out along a bell curve, and ignores gravity and collisions.
 *
 * <p>The original lived 1000 ticks but was fully transparent for the second half, so this one is simply removed at the
 * halfway point (identical look, half as many particles to draw).
 */
public class CloudParticle extends SingleQuadParticle {
	private static final int ORIGINAL_LIFETIME = 1000;
	private static final int VISIBLE_LIFETIME = ORIGINAL_LIFETIME / 2;

	public CloudParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, TextureAtlasSprite sprite) {
		super(level, x, y, z, vx, vy, vz, sprite);
		this.setSize(80.0F, 80.0F);
		this.scale(86.0F);
		this.setLifetime(ORIGINAL_LIFETIME);
		this.friction = 0.995F;
		this.gravity = 0.0F;
		this.hasPhysics = false;
		this.xd = vx;
		this.yd = vy;
		this.zd = vz;
		this.setAlpha(0.0F);
	}

	@Override
	public void tick() {
		super.tick();

		if (this.age >= VISIBLE_LIFETIME) {
			this.remove();
			return;
		}

		float t = (float) this.age / (float) ORIGINAL_LIFETIME;
		float fadeIn = Mth.clamp(t * 2.0F - 0.1F, 0.0F, 1.0F);
		float fadeOut = 1.0F - Mth.clamp(t * 2.0F, 0.0F, 1.0F);
		this.setAlpha(fadeIn * fadeOut * 3.5F);
	}

	@Override
	protected Layer getLayer() {
		return Layer.TRANSLUCENT;
	}
}
