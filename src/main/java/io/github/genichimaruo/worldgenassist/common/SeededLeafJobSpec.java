package io.github.genichimaruo.worldgenassist.common;

import net.minecraft.resources.Identifier;

/** Validated seed-free job fields awaiting server-issued identity and authorization. */
public record SeededLeafJobSpec(
	Identifier dimension,
	int chunkX,
	int chunkZ,
	Identifier noiseSettings,
	int minY,
	int height,
	int cellWidth,
	int cellHeight,
	SeededLeafTranscript transcript
) {
	public SeededLeafJobSpec {
		SeededLeafJob.validateSpec(
			dimension,
			chunkX,
			chunkZ,
			noiseSettings,
			minY,
			height,
			cellWidth,
			cellHeight,
			transcript
		);
	}

	public int sampleCount() {
		return Math.multiplyExact(TerrainDensityJob.CHUNK_SIDE * TerrainDensityJob.CHUNK_SIDE, height);
	}
}
