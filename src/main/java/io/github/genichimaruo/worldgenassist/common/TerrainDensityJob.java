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
	int cellHeight,
	TerrainWorkKind workKind
) {
	public static final int CHUNK_SIDE = 16;
	public static final int MAX_HEIGHT = 384;
	public static final int MAX_SAMPLE_COUNT = CHUNK_SIDE * CHUNK_SIDE * MAX_HEIGHT;
	// The unported fixture retains MAX_SAMPLE_COUNT; only general terrain v7 expands.
	public static final int MAX_TERRAIN_SAMPLE_COUNT = MAX_SAMPLE_COUNT + SurfaceDensityData.SAMPLE_COUNT;
	public static final int MAX_NOISE_SETTINGS_ID_UTF8_BYTES = 256;
	public TerrainDensityJob(TerrainJobIdentity identity, long worldSeed, boolean generateStructures,
		Identifier noiseSettings, int minY, int height, int cellWidth, int cellHeight) {
		this(identity, worldSeed, generateStructures, noiseSettings, minY, height, cellWidth, cellHeight, TerrainWorkKind.DENSITY);
	}

	public TerrainDensityJob {
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		Objects.requireNonNull(workKind, "workKind");
		if (workKind != TerrainWorkKind.DENSITY && (cellWidth != 1 || cellHeight != 1)) {
			throw new IllegalArgumentException("Surface fields require block-aligned job geometry");
		}
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
		if (workKind == TerrainWorkKind.COMPLETE_TERRAIN && (height % 16 != 0 || Math.floorMod(minY, 16) != 0)) {
			throw new IllegalArgumentException("Complete terrain must align to sections");
		}
		if ((workKind == TerrainWorkKind.GRID_AND_SURFACE || workKind == TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE)
			&& (height % 8 != 0 || Math.floorMod(minY, 8) != 0)) {
			throw new IllegalArgumentException("Terrain grid must align to vanilla's vertical cells");
		}
	}

	public int sampleCount() {
		return switch (workKind) {
			case SURFACE_FIELDS -> SurfaceDensityData.SAMPLE_COUNT;
			case GRID_AND_SURFACE -> GridDensityData.sampleCount(height);
			case BLOCK_DENSITY_AND_SURFACE -> BlockDensityData.sampleCount(height);
			case TERRAIN_DECISIONS_AND_SURFACE -> BlockDensityData.sampleCount(height);
			case COMPLETE_TERRAIN -> Math.multiplyExact(CHUNK_SIDE * CHUNK_SIDE, height);
			case DENSITY -> Math.multiplyExact(CHUNK_SIDE * CHUNK_SIDE, height);
		};
	}
}
