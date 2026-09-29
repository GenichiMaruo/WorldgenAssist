package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.random.RandomGenerator;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;

/** Independent, bounded server sampling of the 26.3 block-indexed float volume. */
final class RemoteDensityValidator {
	private static final int VALUES_PER_GROUP = 128;

	private RemoteDensityValidator() { }

	static ValidationMetrics validate(
		TerrainDensityJob job,
		TerrainDensityResult result,
		int sampleGroups,
		RandomState randomState,
		NoiseGeneratorSettings settings,
		NoiseSettings noiseSettings,
		RandomGenerator random
	) {
		return prepare(job, sampleGroups, randomState, settings, noiseSettings, random).compare(result);
	}

	static Prepared prepare(TerrainDensityJob job, int sampleGroups, RandomState randomState,
		NoiseGeneratorSettings settings, NoiseSettings noiseSettings, RandomGenerator random) {
		long start = System.nanoTime();
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(randomState, "randomState");
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		Objects.requireNonNull(random, "random");
		if (job.cellWidth() != 1 || job.cellHeight() != 1
			|| job.minY() != noiseSettings.minY() || job.height() != noiseSettings.height()) {
			throw new RemoteDensityValidationException("Result geometry does not match the authoritative 26.3 volume");
		}
		if (sampleGroups < 0 || sampleGroups > RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS) {
			throw new IllegalArgumentException("Invalid validation group count: " + sampleGroups);
		}
		if (sampleGroups == 0) { return new Prepared(job, 0, new int[0], new float[0], 0L); }

		int totalValues = job.sampleCount();
		int totalGroups = Math.floorDiv(totalValues + VALUES_PER_GROUP - 1, VALUES_PER_GROUP);
		int[] groups = selectCells(totalGroups, Math.min(sampleGroups, totalGroups), random);
		DensitySampler.Bound sampler = randomState.samplersWithContext(SamplerContext.EMPTY_UNCACHED)
			.get(settings.noiseRouter().finalDensity());
		Map<Integer, float[]> sampledColumns = new HashMap<>();
		int chunkMinX = Math.multiplyExact(job.identity().chunkX(), TerrainDensityJob.CHUNK_SIDE);
		int chunkMinZ = Math.multiplyExact(job.identity().chunkZ(), TerrainDensityJob.CHUNK_SIDE);
		int[] indices = new int[Math.min(sampleGroups, totalGroups) * VALUES_PER_GROUP];
		float[] expectedValues = new float[indices.length];
		int validated = 0;
		for (int group : groups) {
			int begin = group * VALUES_PER_GROUP;
			int end = Math.min(totalValues, begin + VALUES_PER_GROUP);
			for (int index = begin; index < end; index++) {
				if (Thread.currentThread().isInterrupted()) {
					throw new CancellationException("Remote density validation interrupted");
				}
				int y = index % job.height();
				int x = (index / job.height()) % TerrainDensityJob.CHUNK_SIDE;
				int z = index / (job.height() * TerrainDensityJob.CHUNK_SIDE);
				int columnKey = z * TerrainDensityJob.CHUNK_SIDE + x;
				float[] column = sampledColumns.get(columnKey);
				if (column == null) {
					DensityVolume columnVolume = new DensityVolume(1, job.height(), 1,
						chunkMinX + x, job.minY(), chunkMinZ + z);
					column = new float[job.height()];
					try (ScopedDensityBuffer buffer = sampler.sampleVolume(columnVolume)) {
						for (int columnY = 0; columnY < column.length; columnY++) {
							column[columnY] = buffer.get(columnY);
						}
					}
					sampledColumns.put(columnKey, column);
				}
				indices[validated] = index;
				expectedValues[validated] = column[y];
				validated++;
			}
		}
		return new Prepared(job, groups.length, java.util.Arrays.copyOf(indices, validated),
			java.util.Arrays.copyOf(expectedValues, validated), System.nanoTime() - start);
	}

	/** Immutable server-owned sample; positions are never sent to the worker. */
	static final class Prepared {
		private final TerrainDensityJob job;
		private final int groups;
		private final int[] indices;
		private final float[] expected;
		private final long prepareNanos;

		private Prepared(TerrainDensityJob job, int groups, int[] indices, float[] expected, long prepareNanos) {
			this.job = job; this.groups = groups; this.indices = indices; this.expected = expected;
			this.prepareNanos = prepareNanos;
		}

		long prepareNanos() { return prepareNanos; }
		ValidationMetrics compare(TerrainDensityResult result) {
			long started = System.nanoTime();
			if (!job.identity().equals(result.identity()) || result.densityCount() != job.sampleCount()) {
				throw new RemoteDensityValidationException("Result identity or volume size does not match its job");
			}
			for (int index = 0; index < indices.length; index++) {
				requireExact(expected[index], result.densityAt(indices[index]), indices[index]);
			}
			return new ValidationMetrics(groups, indices.length, prepareNanos + System.nanoTime() - started);
		}
	}

	static int[] selectCells(int totalCells, int sampleCells, RandomGenerator random) {
		if (totalCells < 1 || sampleCells < 0 || sampleCells > totalCells) {
			throw new IllegalArgumentException("Invalid validation selection geometry");
		}
		Objects.requireNonNull(random, "random");
		boolean[] selected = new boolean[totalCells];
		int remaining = sampleCells;
		while (remaining > 0) {
			int candidate = random.nextInt(totalCells);
			if (!selected[candidate]) { selected[candidate] = true; remaining--; }
		}
		int[] output = new int[sampleCells];
		int index = 0;
		for (int cell = 0; cell < selected.length; cell++) {
			if (selected[cell]) { output[index++] = cell; }
		}
		return output;
	}

	static void requireExact(double expected, double actual, int densityIndex) {
		float value = (float) actual;
		if (!Double.isFinite(actual) || (double)value != actual
			|| Float.floatToRawIntBits((float)expected) != Float.floatToRawIntBits(value)) {
			throw new RemoteDensityValidationException(
				"Remote density validation failed at index " + densityIndex + ": expected=" + expected + ", actual=" + actual
			);
		}
	}

	record ValidationMetrics(int sampledCells, int sampledValues, long elapsedNanos) {
		private static final ValidationMetrics NONE = new ValidationMetrics(0, 0, 0L);
		ValidationMetrics {
			if (sampledCells < 0 || sampledValues < 0 || elapsedNanos < 0L) {
				throw new IllegalArgumentException("Validation metrics must not be negative");
			}
		}
	}

	static final class RemoteDensityValidationException extends IllegalArgumentException {
		RemoteDensityValidationException(String message) { super(message); }
	}
}
