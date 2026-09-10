package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.CancellationException;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import io.github.genichimaruo.worldgenassist.common.SeededLeafJobSpec;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;

/** Discovers one exact job-bounded seeded-leaf transcript using server-owned state. */
public final class SeededLeafJobSpecRecorder {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	private SeededLeafJobSpecRecorder() {
	}

	public static RecordedSpec record(
		Identifier dimension,
		int chunkX,
		int chunkZ,
		RandomState randomState,
		Holder<NoiseGeneratorSettings> settingsHolder,
		NoiseSettings noiseSettings,
		int maximumTranscriptEntries
	) {
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(randomState, "randomState");
		Objects.requireNonNull(settingsHolder, "settingsHolder");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		var settingsKey = settingsHolder.unwrapKey()
			.orElseThrow(() -> new IllegalArgumentException("Noise generator settings must be registry-backed"));
		if (!OVERWORLD.equals(dimension) || !NoiseGeneratorSettings.OVERWORLD.equals(settingsKey)) {
			throw new IllegalArgumentException("Seeded-leaf recording currently supports only minecraft:overworld");
		}
		Identifier noiseSettingsId = settingsKey.identifier();
		SeededLeafJob.validateGeometry(dimension, chunkX, chunkZ, noiseSettingsId,
			noiseSettings.minY(), noiseSettings.height(), noiseSettings.getCellWidth(), noiseSettings.getCellHeight());
		NoiseGeneratorSettings settings = settingsHolder.value();
		NoiseSettings configuredNoise = settings.noiseSettings();
		if (configuredNoise.noiseSizeHorizontal() != noiseSettings.noiseSizeHorizontal()
			|| configuredNoise.noiseSizeVertical() != noiseSettings.noiseSizeVertical()
			|| noiseSettings.minY() < configuredNoise.minY()
			|| Math.addExact(noiseSettings.minY(), noiseSettings.height())
				> Math.addExact(configuredNoise.minY(), configuredNoise.height())) {
			throw new IllegalArgumentException("Clamped noise settings do not belong to the generator settings");
		}
		if (!ChunkPos.isValid(chunkX, chunkZ)) {
			throw new IllegalArgumentException("Chunk coordinate is outside Minecraft generation bounds");
		}
		if (maximumTranscriptEntries < 1 || maximumTranscriptEntries > SeededLeafTranscript.MAX_ENTRIES) {
			throw new IllegalArgumentException(
				"maximumTranscriptEntries must be between 1 and " + SeededLeafTranscript.MAX_ENTRIES
			);
		}

		long startedNanos = System.nanoTime();
		SeededLeafTrace.Recording recording = SeededLeafTrace.beginRecording(
			randomState.router(),
			maximumTranscriptEntries
		);
		int samples;
		try (recording) {
			samples = new DiscoverySampler(chunkX, chunkZ, randomState, settings, noiseSettings).discover();
		}
		SeededLeafJobSpec spec = new SeededLeafJobSpec(
			dimension,
			chunkX,
			chunkZ,
			noiseSettingsId,
			noiseSettings.minY(),
			noiseSettings.height(),
			noiseSettings.getCellWidth(),
			noiseSettings.getCellHeight(),
			recording.transcript()
		);
		if (samples != spec.sampleCount()) {
			throw new IllegalStateException("Recorded seeded-leaf traversal did not cover the exact density shape");
		}
		return new RecordedSpec(spec, samples, System.nanoTime() - startedNanos);
	}

	public record RecordedSpec(SeededLeafJobSpec spec, int sampledDensities, long elapsedNanos) {
		public RecordedSpec {
			Objects.requireNonNull(spec, "spec");
			if (sampledDensities != spec.sampleCount()) {
				throw new IllegalArgumentException("sampledDensities does not match the recorded job shape");
			}
			if (elapsedNanos < 0L) {
				throw new IllegalArgumentException("elapsedNanos must not be negative");
			}
		}
	}

	private static Aquifer.FluidPicker unusedFluidPicker() {
		Aquifer.FluidStatus empty = new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
		return (x, y, z) -> empty;
	}

	private static final class DiscoverySampler extends NoiseChunk {
		private final int chunkMinX;
		private final int chunkMinZ;
		private final NoiseSettings noiseSettings;

		private DiscoverySampler(
			int chunkX,
			int chunkZ,
			RandomState randomState,
			NoiseGeneratorSettings settings,
			NoiseSettings noiseSettings
		) {
			super(
				TerrainDensityJob.CHUNK_SIDE / noiseSettings.getCellWidth(),
				randomState,
				Math.multiplyExact(chunkX, TerrainDensityJob.CHUNK_SIDE),
				Math.multiplyExact(chunkZ, TerrainDensityJob.CHUNK_SIDE),
				noiseSettings,
				Beardifier.EMPTY,
				settings,
				unusedFluidPicker(),
				Blender.empty()
			);
			this.chunkMinX = Math.multiplyExact(chunkX, TerrainDensityJob.CHUNK_SIDE);
			this.chunkMinZ = Math.multiplyExact(chunkZ, TerrainDensityJob.CHUNK_SIDE);
			this.noiseSettings = noiseSettings;
		}

		private int discover() {
			int cellWidth = noiseSettings.getCellWidth();
			int cellHeight = noiseSettings.getCellHeight();
			int cellCountXZ = TerrainDensityJob.CHUNK_SIDE / cellWidth;
			int cellCountY = noiseSettings.height() / cellHeight;
			int samples = 0;
			initializeForFirstCellX();
			try {
				for (int cellX = 0; cellX < cellCountXZ; cellX++) {
					checkCancelled();
					advanceCellX(cellX);
					for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
						checkCancelled();
						for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
							checkCancelled();
							selectCellYZ(cellY, cellZ);
							for (int yInCell = cellHeight - 1; yInCell >= 0; yInCell--) {
								int blockY = noiseSettings.minY() + cellY * cellHeight + yInCell;
								updateForY(blockY, (double)yInCell / cellHeight);
								for (int xInCell = 0; xInCell < cellWidth; xInCell++) {
									updateForX(chunkMinX + cellX * cellWidth + xInCell, (double)xInCell / cellWidth);
									for (int zInCell = 0; zInCell < cellWidth; zInCell++) {
										updateForZ(chunkMinZ + cellZ * cellWidth + zInCell, (double)zInCell / cellWidth);
										getInterpolatedDensity();
										samples++;
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
			return samples;
		}

		private static void checkCancelled() {
			if (Thread.currentThread().isInterrupted()) {
				throw new CancellationException("Seeded-leaf transcript recording was interrupted");
			}
		}
	}
}
