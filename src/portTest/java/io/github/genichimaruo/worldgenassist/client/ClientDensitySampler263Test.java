package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Compares the 26.3 worker path with doFill's caching sampler context. */
class ClientDensitySampler263Test {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@Timeout(90)
	void matchesVanillaVolumeInThreeDimensions() {
		HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
		check(lookup, NoiseGeneratorSettings.OVERWORLD, 8675309L, 0, 0);
		check(lookup, NoiseGeneratorSettings.NETHER, -987654321L, -11, 7);
		check(lookup, NoiseGeneratorSettings.END, 123456789L, 23, -19);
	}

	private static void check(HolderLookup.Provider lookup,
		net.minecraft.resources.ResourceKey<NoiseGeneratorSettings> key,
		long seed, int chunkX, int chunkZ) {
		NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
		NoiseSettings noise = settings.noiseSettings();
		RandomState state = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings);
		double[] worker = new ClientDensitySampler(chunkX, chunkZ, noise.minY(), noise.height(), 1, 1,
			state, settings, noise).sample().densities();
		DensityVolume volume = new DensityVolume(16, noise.height(), 16, chunkX * 16, noise.minY(), chunkZ * 16);
		DensityBufferPool pool = state.acquireDensityBufferPool();
		try {
			SamplerContext context = SamplerContext.builder()
				.setUserFields(ContextMap.builder().set(Beardifier.CONTEXT_KEY, Beardifier.EMPTY).build())
				.useBufferArena(pool).enableCaches().build();
			try (ScopedDensityBuffer vanilla = state.samplersWithContext(context)
				.get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
				assertEquals(volume.size(), worker.length);
				for (int index = 0; index < worker.length; index++) {
					assertEquals(Float.floatToRawIntBits(vanilla.get(index)),
						Float.floatToRawIntBits((float) worker[index]), key + " index=" + index);
				}
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					DensityVolume column = new DensityVolume(1, noise.height(), 1,
						volume.blockX(x), noise.minY(), volume.blockZ(z));
					try (ScopedDensityBuffer sampled = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED)
						.get(settings.noiseRouter().finalDensity()).sampleVolume(column)) {
						for (int y = 0; y < noise.height(); y++) {
							int index = volume.indexUnchecked(x, y, z);
							assertEquals(Float.floatToRawIntBits(vanilla.get(index)),
								Float.floatToRawIntBits(sampled.get(y)), key + " validation index=" + index);
						}
					}
				}
			}
			}
		} finally {
			state.releaseDensityBufferPool(pool);
		}
	}
}
