package io.github.genichimaruo.worldgenassist.common;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import net.minecraft.resources.Identifier;

public record TerrainDensityJob(
	TerrainJobIdentity identity,
	long worldSeed,
	boolean generateStructures,
	Identifier noiseSettings,
	int minY,
	int height,
	int cellWidth,
	int cellHeight
) {
	public static final int CHUNK_SIDE = 16;
	public static final int MAX_HEIGHT = 384;
	public static final int MAX_SAMPLE_COUNT = CHUNK_SIDE * CHUNK_SIDE * MAX_HEIGHT;
	public static final int MAX_NOISE_SETTINGS_ID_UTF8_BYTES = 256;

	public TerrainDensityJob {
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		int settingsIdBytes = noiseSettings.toString().getBytes(StandardCharsets.UTF_8).length;
		if (settingsIdBytes > MAX_NOISE_SETTINGS_ID_UTF8_BYTES) {
			throw new IllegalArgumentException(
				"Noise settings identifier exceeds " + MAX_NOISE_SETTINGS_ID_UTF8_BYTES + " UTF-8 bytes: " + settingsIdBytes
			);
		}
		if (height <= 0 || height > MAX_HEIGHT) {
			throw new IllegalArgumentException("Generation height must be between 1 and " + MAX_HEIGHT + ": " + height);
		}
		if (cellWidth <= 0 || cellWidth > CHUNK_SIDE || CHUNK_SIDE % cellWidth != 0) {
			throw new IllegalArgumentException("Cell width must be a positive divisor of " + CHUNK_SIDE + ": " + cellWidth);
		}
		if (cellHeight <= 0 || cellHeight > height || height % cellHeight != 0) {
			throw new IllegalArgumentException("Cell height must be a positive divisor of generation height: " + cellHeight);
		}
		if (Math.floorMod(minY, cellHeight) != 0) {
			throw new IllegalArgumentException("Minimum Y must align to the cell height: minY=" + minY + " cellHeight=" + cellHeight);
		}
		Math.addExact(minY, height);
	}

	public int sampleCount() {
		return Math.multiplyExact(CHUNK_SIDE * CHUNK_SIDE, height);
	}
}
