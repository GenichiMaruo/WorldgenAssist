package io.github.genichimaruo.worldgenassist.client;

import java.util.Objects;
import java.util.concurrent.CancellationException;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;

/** Computes the exact full-block final-density volume used by 26.3 doFill. */
final class ClientDensitySampler {
	private final int chunkX;
	private final int chunkZ;
	private final int minY;
	private final int height;
	private final RandomState randomState;
	private final NoiseGeneratorSettings settings;

	ClientDensitySampler(int chunkX, int chunkZ, int minY, int height, int cellWidth, int cellHeight,
		RandomState randomState, NoiseGeneratorSettings settings, NoiseSettings noiseSettings) {
		this.randomState = Objects.requireNonNull(randomState, "randomState");
		this.settings = Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		if (cellWidth != 1 || cellHeight != 1 || height < 1 || height > TerrainDensityJob.MAX_HEIGHT
			|| noiseSettings.minY() != minY || noiseSettings.height() != height) {
			throw new IllegalArgumentException("Unsupported 26.3 density volume geometry");
		}
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.minY = minY;
		this.height = height;
	}

	Sample sample() {
		if (Thread.currentThread().isInterrupted()) { throw new CancellationException("Remote terrain job cancelled"); }
		long start = System.nanoTime();
		DensityVolume volume = new DensityVolume(16, height, 16,
			Math.multiplyExact(chunkX, 16), minY, Math.multiplyExact(chunkZ, 16));
		double[] densities = new double[volume.size()];
		try (ScopedDensityBuffer buffer = randomState
			.samplersWithContext(SamplerContext.EMPTY_UNCACHED)
			.get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
			for (int index = 0; index < densities.length; index++) {
				if ((index & 255) == 0 && Thread.currentThread().isInterrupted()) {
					throw new CancellationException("Remote terrain job cancelled");
				}
				float density = buffer.get(index);
				if (!Float.isFinite(density)) { throw new IllegalArgumentException("Non-finite density at " + index); }
				densities[index] = density;
			}
		}
		return new Sample(densities, System.nanoTime() - start);
	}

	record Sample(double[] densities, long computeNanos) {
		Sample {
			densities = densities.clone();
			if (computeNanos < 0L) { throw new IllegalArgumentException("computeNanos must not be negative"); }
		}
		@Override public double[] densities() { return densities.clone(); }
	}
}
