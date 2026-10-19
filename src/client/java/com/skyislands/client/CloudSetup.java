package com.skyislands.client;

import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;

import com.skyislands.SkyIslands;

/** Registers the cloud particle type and its provider (textures: assets/sky_islands/particles/cloud.json). */
public final class CloudSetup {
	/** alwaysSpawn = true: vanilla otherwise refuses to spawn particles more than 32 blocks from the camera. */
	public static final SimpleParticleType CLOUD = FabricParticleTypes.simple(true);

	private CloudSetup() { }

	public static void register() {
		Registry.register(BuiltInRegistries.PARTICLE_TYPE, SkyIslands.id("cloud"), CLOUD);
		ParticleProviderRegistry.getInstance().register(CLOUD, CloudProvider::new);
	}

	private static final class CloudProvider implements ParticleProvider<SimpleParticleType> {
		private final FabricSpriteSet spriteSet;

		CloudProvider(FabricSpriteSet spriteSet) {
			this.spriteSet = spriteSet;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz, RandomSource random) {
			return new CloudParticle(level, x, y, z, vx, vy, vz, this.spriteSet.get(random));
		}
	}
}
