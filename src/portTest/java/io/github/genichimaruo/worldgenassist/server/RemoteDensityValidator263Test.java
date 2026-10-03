package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import java.util.UUID;
import java.util.Arrays;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RemoteDensityValidator263Test {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@Timeout(45)
	void acceptsExactVolumeAndRejectsChangedSample() {
		HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
		NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
		var noise = settings.noiseSettings();
		RandomState state = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), 8675309L, settings);
		TerrainJobIdentity identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,
			UUID.randomUUID(), Identifier.parse("minecraft:overworld"), 7, -11,
			WorldgenContextFingerprint.fromBytes(new byte[32]));
		TerrainDensityJob job = new TerrainDensityJob(identity, 8675309L, true,
			NoiseGeneratorSettings.OVERWORLD.identifier(), noise.minY(), noise.height(), 1, 1);
		DensityVolume volume = new DensityVolume(16, noise.height(), 16, 7 * 16, noise.minY(), -11 * 16);
		double[] values = new double[volume.size()];
		try (ScopedDensityBuffer buffer = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED)
			.get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
			for (int index = 0; index < values.length; index++) { values[index] = buffer.get(index); }
		}
		TerrainDensityResult result = new TerrainDensityResult(identity, values, 1L);
		TerrainDensityResult received = TerrainDensityResultEnvelope.encode(result).decode();
		RemoteDensityField field = new RemoteDensityField(job, received);
		try (ScopedDensityBuffer copied = SamplerContext.EMPTY_UNCACHED.acquireBuffer(volume)) {
			field.copyVolume(volume, copied);
			for (int index = 0; index < values.length; index++) {
				assertEquals(Float.floatToRawIntBits((float) values[index]),
					Float.floatToRawIntBits(copied.get(index)), "transported index=" + index);
			}
		}
		assertEquals(128, RemoteDensityValidator.validate(job, received, 1, state, settings, noise,
			new Random(1)).sampledValues());

		int sampledGroup = RemoteDensityValidator.selectCells(values.length / 128, 1, new Random(1))[0];
		values[sampledGroup * 128] += 1.0;
		TerrainDensityResult altered = new TerrainDensityResult(identity, values, 1L);
		assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,
			() -> RemoteDensityValidator.validate(job, altered, 1, state, settings, noise, new Random(1)));
	}

	@Test
	@Timeout(90)
	void cachedPrefixesPreserveSamplesAndMeasurePreparationCost() {
		HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
		for (var key : java.util.List.of(NoiseGeneratorSettings.OVERWORLD,
			NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END)) {
			NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
			RandomState state = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), 8675309L, settings);
			for (NoiseSettings noise : java.util.List.of(settings.noiseSettings(), NoiseSettings.create(-16, 48))) {
				TerrainJobIdentity identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,
					UUID.randomUUID(), key.identifier(), -7, 11, WorldgenContextFingerprint.fromBytes(new byte[32]));
				TerrainDensityJob job = new TerrainDensityJob(identity, 8675309L, true, key.identifier(),
					noise.minY(), noise.height(), 1, 1);
				DensityVolume volume = new DensityVolume(16, noise.height(), 16, -7 * 16, noise.minY(), 11 * 16);
				double[] values = new double[volume.size()];
				try (ScopedDensityBuffer buffer = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED)
					.get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
					for (int index = 0; index < values.length; index++) { values[index] = buffer.get(index); }
				}
				TerrainDensityResult result = new TerrainDensityResult(identity, values, 0L);
				for (int groups : new int[] {8, 64}) {
					for (int seed = 0; seed < 3; seed++) {
						assertEquals(groups * 128, RemoteDensityValidator.validate(job, result, groups,
							state, settings, noise, new Random(seed)).sampledValues());
					}
				}
				if (key.equals(NoiseGeneratorSettings.OVERWORLD) && noise.equals(settings.noiseSettings())) {
					// Timing is descriptive only; correctness must never depend on host speed.
					long[] oldTimes = new long[21]; long[] newTimes = new long[21]; long[] fullTimes = new long[21];
					for (int iteration = -20; iteration < oldTimes.length; iteration++) {
						long oldTime = measureOriginalColumns(job, state, settings, iteration);
						long newTime = RemoteDensityValidator.prepare(job, 8, state, settings, noise,
							new Random(iteration)).prepareNanos();
						long start = System.nanoTime();
						var pool = state.acquireDensityBufferPool();
						try (ScopedDensityBuffer ignored = state.samplersWithContext(SamplerContext.builder()
							.useBufferArena(pool).enableCaches().build()).get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
							assertEquals(values.length, volume.size());
						} finally { state.releaseDensityBufferPool(pool); }
						if (iteration >= 0) {
							oldTimes[iteration] = oldTime; newTimes[iteration] = newTime; fullTimes[iteration] = System.nanoTime() - start;
						}
					}
					Arrays.sort(oldTimes); Arrays.sort(newTimes); Arrays.sort(fullTimes);
					System.out.printf(java.util.Locale.ROOT,
						"VALIDATION_PREPARATION_MEDIAN original_ms=%.6f candidate_ms=%.6f vanilla_cached_full_ms=%.6f samples=21 groups=8 values=1024%n",
						oldTimes[10] / 1_000_000.0, newTimes[10] / 1_000_000.0, fullTimes[10] / 1_000_000.0);
				}
			}
		}
	}

	private static long measureOriginalColumns(TerrainDensityJob job, RandomState state,
		NoiseGeneratorSettings settings, int seed) {
		long start = System.nanoTime();
		var sampler = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED).get(settings.noiseRouter().finalDensity());
		java.util.Set<Integer> columns = new java.util.HashSet<>();
		for (int group : RemoteDensityValidator.selectCells(job.sampleCount() / 128, 8, new Random(seed))) {
			int column = group * 128 / job.height();
			if (!columns.add(column)) { continue; }
			DensityVolume volume = new DensityVolume(1, job.height(), 1,
				job.identity().chunkX() * 16 + column % 16, job.minY(), job.identity().chunkZ() * 16 + column / 16);
			try (ScopedDensityBuffer buffer = sampler.sampleVolume(volume)) {
				assertEquals(job.height(), volume.sizeY());
			}
		}
		return System.nanoTime() - start;
	}
}
