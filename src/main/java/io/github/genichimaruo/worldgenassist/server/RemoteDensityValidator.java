package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.function.IntToDoubleFunction;
import java.util.random.RandomGenerator;
import java.util.concurrent.CancellationException;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;

final class RemoteDensityValidator {
	private RemoteDensityValidator() {
	}

	static ValidationMetrics validate(
		TerrainDensityJob job,
		TerrainDensityResult result,
		int sampleCells,
		RandomState randomState,
		NoiseGeneratorSettings settings,
		NoiseSettings noiseSettings,
		RandomGenerator random
	) {
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(randomState, "randomState");
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		Objects.requireNonNull(random, "random");
		if (!job.identity().equals(result.identity())) {
			throw new RemoteDensityValidationException("Validation result identity does not match its job");
		}
		if (result.densityCount() != job.sampleCount()) {
			throw new RemoteDensityValidationException(
				"Validation result count does not match its job: " + result.densityCount() + " != " + job.sampleCount()
			);
		}
		return validateGeometry(
			job.identity().chunkX(),
			job.identity().chunkZ(),
			job.minY(),
			job.height(),
			job.cellWidth(),
			job.cellHeight(),
			result::densityAt,
			sampleCells,
			randomState,
			settings,
			noiseSettings,
			random
		);
	}

	static ValidationMetrics validate(
		SeededLeafDensityResultGate.AcceptedResult accepted,
		int sampleCells,
		RandomState randomState,
		NoiseGeneratorSettings settings,
		NoiseSettings noiseSettings,
		RandomGenerator random
	) {
		Objects.requireNonNull(accepted, "accepted");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		int expectedCount = Math.multiplyExact(
			TerrainDensityJob.CHUNK_SIDE * TerrainDensityJob.CHUNK_SIDE,
			accepted.height()
		);
		if (accepted.densityCount() != expectedCount) {
			throw new RemoteDensityValidationException(
				"Seeded-leaf result count does not match its geometry: " + accepted.densityCount() + " != " + expectedCount
			);
		}
		if (accepted.minY() != noiseSettings.minY()
			|| accepted.height() != noiseSettings.height()
			|| accepted.cellWidth() != noiseSettings.getCellWidth()
			|| accepted.cellHeight() != noiseSettings.getCellHeight()) {
			throw new RemoteDensityValidationException("Seeded-leaf result geometry does not match authoritative noise settings");
		}
		return validateGeometry(
			accepted.chunkX(),
			accepted.chunkZ(),
			accepted.minY(),
			accepted.height(),
			accepted.cellWidth(),
			accepted.cellHeight(),
			accepted.densityResult()::densityAt,
			sampleCells,
			randomState,
			settings,
			noiseSettings,
			random
		);
	}

	private static ValidationMetrics validateGeometry(
		int chunkX,
		int chunkZ,
		int minY,
		int height,
		int cellWidth,
		int cellHeight,
		IntToDoubleFunction densityAt,
		int sampleCells,
		RandomState randomState,
		NoiseGeneratorSettings settings,
		NoiseSettings noiseSettings,
		RandomGenerator random
	) {
		Objects.requireNonNull(densityAt, "densityAt");
		Objects.requireNonNull(randomState, "randomState");
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		Objects.requireNonNull(random, "random");
		if (sampleCells < 0 || sampleCells > RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS) {
			throw new IllegalArgumentException(
				"sampleCells must be between 0 and " + RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS + ": " + sampleCells
			);
		}
		if (sampleCells == 0) {
			return ValidationMetrics.NONE;
		}

		int cellCountXZ = TerrainDensityJob.CHUNK_SIDE / cellWidth;
		int cellCountY = height / cellHeight;
		int totalCells = Math.multiplyExact(Math.multiplyExact(cellCountXZ, cellCountXZ), cellCountY);
		int[] selectedCells = selectCells(totalCells, Math.min(sampleCells, totalCells), random);
		long startedNanos = System.nanoTime();
		ValidationSampler sampler = new ValidationSampler(
			chunkX,
			chunkZ,
			minY,
			cellWidth,
			cellHeight,
			randomState,
			settings,
			noiseSettings
		);
		int sampledValues = sampler.validateSelectedCells(densityAt, selectedCells, cellCountXZ, cellCountY);
		return new ValidationMetrics(selectedCells.length, sampledValues, System.nanoTime() - startedNanos);
	}

	static int[] selectCells(int totalCells, int sampleCells, RandomGenerator random) {
		if (totalCells < 1) {
			throw new IllegalArgumentException("totalCells must be positive: " + totalCells);
		}
		if (sampleCells < 0 || sampleCells > totalCells) {
			throw new IllegalArgumentException("sampleCells must be between 0 and totalCells: " + sampleCells);
		}
		Objects.requireNonNull(random, "random");
		boolean[] selected = new boolean[totalCells];
		int remaining = sampleCells;
		while (remaining > 0) {
			int candidate = random.nextInt(totalCells);
			if (!selected[candidate]) {
				selected[candidate] = true;
				remaining--;
			}
		}
		int[] cells = new int[sampleCells];
		int output = 0;
		for (int cell = 0; cell < selected.length; cell++) {
			if (selected[cell]) {
				cells[output++] = cell;
			}
		}
		return cells;
	}

	static void requireExact(double expected, double actual, int densityIndex) {
		if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
			throw new RemoteDensityValidationException(
				"Remote density validation failed at index " + densityIndex + ": expected=" + expected + ", actual=" + actual
			);
		}
	}

	private static Aquifer.FluidPicker unusedFluidPicker() {
		Aquifer.FluidStatus empty = new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
		return (x, y, z) -> empty;
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
		RemoteDensityValidationException(String message) {
			super(message);
		}
	}

	private static final class ValidationSampler extends NoiseChunk {
		private final int chunkX;
		private final int chunkZ;
		private final int minY;
		private final int cellWidth;
		private final int cellHeight;

		private ValidationSampler(
			int chunkX,
			int chunkZ,
			int minY,
			int cellWidth,
			int cellHeight,
			RandomState randomState,
			NoiseGeneratorSettings settings,
			NoiseSettings noiseSettings
		) {
			super(
				TerrainDensityJob.CHUNK_SIDE / cellWidth,
				randomState,
				Math.multiplyExact(chunkX, TerrainDensityJob.CHUNK_SIDE),
				Math.multiplyExact(chunkZ, TerrainDensityJob.CHUNK_SIDE),
				noiseSettings,
				Beardifier.EMPTY,
				settings,
				unusedFluidPicker(),
				Blender.empty()
			);
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.minY = minY;
			this.cellWidth = cellWidth;
			this.cellHeight = cellHeight;
		}

		private int validateSelectedCells(
			IntToDoubleFunction densityAt,
			int[] selectedCells,
			int cellCountXZ,
			int cellCountY
		) {
			boolean[] selected = new boolean[Math.multiplyExact(Math.multiplyExact(cellCountXZ, cellCountXZ), cellCountY)];
			int maxCellX = 0;
			for (int cell : selectedCells) {
				selected[cell] = true;
				maxCellX = Math.max(maxCellX, cell / (cellCountXZ * cellCountY));
			}

			int validatedValues = 0;
			initializeForFirstCellX();
			try {
				for (int cellX = 0; cellX <= maxCellX; cellX++) {
					checkCancelled();
					advanceCellX(cellX);
					for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
						checkCancelled();
						for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
							int cellIndex = (cellX * cellCountXZ + cellZ) * cellCountY + cellY;
							if (selected[cellIndex]) {
								validatedValues += validateCell(densityAt, cellX, cellY, cellZ);
							}
						}
					}
					swapSlices();
				}
			} finally {
				stopInterpolation();
			}
			return validatedValues;
		}

		private int validateCell(IntToDoubleFunction densityAt, int cellX, int cellY, int cellZ) {
			checkCancelled();
			selectCellYZ(cellY, cellZ);
			int cellMinY = Math.floorDiv(minY, cellHeight);
			int chunkMinX = Math.multiplyExact(chunkX, TerrainDensityJob.CHUNK_SIDE);
			int chunkMinZ = Math.multiplyExact(chunkZ, TerrainDensityJob.CHUNK_SIDE);
			int values = 0;
			for (int yInCell = cellHeight - 1; yInCell >= 0; yInCell--) {
				checkCancelled();
				int blockY = (cellMinY + cellY) * cellHeight + yInCell;
				int yOffset = blockY - minY;
				updateForY(blockY, (double)yInCell / cellHeight);
				for (int xInCell = 0; xInCell < cellWidth; xInCell++) {
					int xOffset = cellX * cellWidth + xInCell;
					updateForX(chunkMinX + xOffset, (double)xInCell / cellWidth);
					for (int zInCell = 0; zInCell < cellWidth; zInCell++) {
						int zOffset = cellZ * cellWidth + zInCell;
						updateForZ(chunkMinZ + zOffset, (double)zInCell / cellWidth);
						int densityIndex = (yOffset * TerrainDensityJob.CHUNK_SIDE + xOffset) * TerrainDensityJob.CHUNK_SIDE + zOffset;
						requireExact(getInterpolatedDensity(), densityAt.applyAsDouble(densityIndex), densityIndex);
						values++;
					}
				}
			}
			return values;
		}

		private static void checkCancelled() {
			if (Thread.currentThread().isInterrupted()) {
				throw new CancellationException("Remote density validation was interrupted");
			}
		}
	}
}
