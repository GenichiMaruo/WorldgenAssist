package io.github.genichimaruo.worldgenassist.client;

import java.util.Objects;
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

final class ClientDensitySampler extends NoiseChunk {
	private static final Aquifer.FluidStatus EMPTY_FLUID = new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
	private static final Aquifer.FluidPicker UNUSED_FLUID_PICKER = (x, y, z) -> EMPTY_FLUID;

	private final int chunkX;
	private final int chunkZ;
	private final int minY;
	private final int height;
	private final int cellWidth;
	private final int cellHeight;

	ClientDensitySampler(
		int chunkX,
		int chunkZ,
		int minY,
		int height,
		int cellWidth,
		int cellHeight,
		RandomState randomState,
		NoiseGeneratorSettings settings,
		NoiseSettings noiseSettings
	) {
		super(
			validateAndGetCellCountXZ(minY, height, cellWidth, cellHeight, noiseSettings),
			randomState,
			Math.multiplyExact(chunkX, 16),
			Math.multiplyExact(chunkZ, 16),
			noiseSettings,
			Beardifier.EMPTY,
			settings,
			UNUSED_FLUID_PICKER,
			Blender.empty()
		);
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.minY = minY;
		this.height = height;
		this.cellWidth = cellWidth;
		this.cellHeight = cellHeight;
	}

	private static int validateAndGetCellCountXZ(
		int minY,
		int height,
		int cellWidth,
		int cellHeight,
		NoiseSettings noiseSettings
	) {
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		if (height < 1 || height > TerrainDensityJob.MAX_HEIGHT) {
			throw new IllegalArgumentException("Invalid density sampling height: " + height);
		}
		if (cellWidth < 1 || cellWidth > TerrainDensityJob.CHUNK_SIDE
			|| TerrainDensityJob.CHUNK_SIDE % cellWidth != 0) {
			throw new IllegalArgumentException("Invalid density sampling cell width: " + cellWidth);
		}
		if (cellHeight < 1 || cellHeight > height || height % cellHeight != 0
			|| Math.floorMod(minY, cellHeight) != 0) {
			throw new IllegalArgumentException("Invalid density sampling vertical cell geometry");
		}
		Math.addExact(minY, height);
		if (noiseSettings.minY() != minY
			|| noiseSettings.height() != height
			|| noiseSettings.getCellWidth() != cellWidth
			|| noiseSettings.getCellHeight() != cellHeight) {
			throw new IllegalArgumentException("Density sampling geometry does not match NoiseSettings");
		}
		return TerrainDensityJob.CHUNK_SIDE / cellWidth;
	}

	Sample sample() {
		long startedNanos = System.nanoTime();
		double[] densities = new double[Math.multiplyExact(16 * 16, height)];
		int cellCountXZ = 16 / cellWidth;
		int cellCountY = height / cellHeight;
		int cellMinY = Math.floorDiv(minY, cellHeight);
		int chunkMinBlockX = Math.multiplyExact(chunkX, 16);
		int chunkMinBlockZ = Math.multiplyExact(chunkZ, 16);

		initializeForFirstCellX();
		try {
			for (int cellXIndex = 0; cellXIndex < cellCountXZ; cellXIndex++) {
				checkCancelled();
				advanceCellX(cellXIndex);
				for (int cellZIndex = 0; cellZIndex < cellCountXZ; cellZIndex++) {
					checkCancelled();
					for (int cellYIndex = cellCountY - 1; cellYIndex >= 0; cellYIndex--) {
						checkCancelled();
						selectCellYZ(cellYIndex, cellZIndex);
						for (int yInCell = cellHeight - 1; yInCell >= 0; yInCell--) {
							int blockY = (cellMinY + cellYIndex) * cellHeight + yInCell;
							int yOffset = blockY - minY;
							updateForY(blockY, (double)yInCell / cellHeight);
							for (int xInCell = 0; xInCell < cellWidth; xInCell++) {
								int xOffset = cellXIndex * cellWidth + xInCell;
								int blockX = chunkMinBlockX + xOffset;
								updateForX(blockX, (double)xInCell / cellWidth);
								for (int zInCell = 0; zInCell < cellWidth; zInCell++) {
									int zOffset = cellZIndex * cellWidth + zInCell;
									int blockZ = chunkMinBlockZ + zOffset;
									updateForZ(blockZ, (double)zInCell / cellWidth);
									densities[index(xOffset, yOffset, zOffset)] = getInterpolatedDensity();
								}
							}
						}
					}
				}
				swapSlices();
			}
		} finally {
			stopInterpolation();
		}

		return new Sample(densities, System.nanoTime() - startedNanos);
	}

	private static int index(int xOffset, int yOffset, int zOffset) {
		return (yOffset * TerrainDensityJob.CHUNK_SIDE + xOffset) * TerrainDensityJob.CHUNK_SIDE + zOffset;
	}

	private static void checkCancelled() {
		if (Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Remote terrain job cancelled");
		}
	}

	record Sample(double[] densities, long computeNanos) {
		Sample {
			densities = densities.clone();
			if (computeNanos < 0L) {
				throw new IllegalArgumentException("computeNanos must not be negative");
			}
		}

		@Override
		public double[] densities() {
			return densities.clone();
		}
	}
}
