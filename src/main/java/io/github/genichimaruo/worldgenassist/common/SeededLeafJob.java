package io.github.genichimaruo.worldgenassist.common;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

/** Unregistered protocol-v3 research input with no raw seed or seed-derived fingerprint field. */
public record SeededLeafJob(
	UUID jobId,
	OpaqueWorldgenContextId contextId,
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
	public static final int FORMAT_VERSION = 1;
	public static final String DENSITY_GRAPH_ID = "minecraft:overworld_final_density/26.2/seeded_leaf_v1";
	public static final int MAX_IDENTIFIER_UTF8_BYTES = 256;

	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	public SeededLeafJob {
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(contextId, "contextId");
		if (jobId.getMostSignificantBits() == 0L && jobId.getLeastSignificantBits() == 0L) {
			throw new IllegalArgumentException("Job ID must not be the all-zero UUID");
		}
		validateSpec(dimension, chunkX, chunkZ, noiseSettings, minY, height, cellWidth, cellHeight, transcript);
	}

	public SeededLeafJob(UUID jobId, OpaqueWorldgenContextId contextId, SeededLeafJobSpec spec) {
		this(
			jobId,
			contextId,
			Objects.requireNonNull(spec, "spec").dimension(),
			spec.chunkX(),
			spec.chunkZ(),
			spec.noiseSettings(),
			spec.minY(),
			spec.height(),
			spec.cellWidth(),
			spec.cellHeight(),
			spec.transcript()
		);
	}

	public SeededLeafJobSpec spec() {
		return new SeededLeafJobSpec(
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

	public SeededLeafJobClaim claim(SeededLeafJobAuthenticationTag authenticationTag) {
		return new SeededLeafJobClaim(jobId, contextId, authenticationTag);
	}

	static void validateSpec(
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
		Objects.requireNonNull(transcript, "transcript");
		validateGeometry(dimension, chunkX, chunkZ, noiseSettings, minY, height, cellWidth, cellHeight);
	}

	/** Validates the exact wire geometry before a server starts discovering a transcript. */
	public static void validateGeometry(
		Identifier dimension, int chunkX, int chunkZ, Identifier noiseSettings,
		int minY, int height, int cellWidth, int cellHeight
	) {
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		if (!OVERWORLD.equals(dimension) || !OVERWORLD.equals(noiseSettings)) {
			throw new IllegalArgumentException(
				"Seeded-leaf draft currently supports only minecraft:overworld dimension and noise settings"
			);
		}
		validateIdentifierLength(dimension, "Dimension");
		validateIdentifierLength(noiseSettings, "Noise settings");
		if (!ChunkPos.isValid(chunkX, chunkZ)) {
			throw new IllegalArgumentException(
				"Chunk coordinate is outside Minecraft's valid generation bounds: " + chunkX + "," + chunkZ
			);
		}
		if (height <= 0 || height > TerrainDensityJob.MAX_HEIGHT) {
			throw new IllegalArgumentException(
				"Generation height must be between 1 and " + TerrainDensityJob.MAX_HEIGHT + ": " + height
			);
		}
		if (cellWidth <= 0 || cellWidth > TerrainDensityJob.CHUNK_SIDE || TerrainDensityJob.CHUNK_SIDE % cellWidth != 0) {
			throw new IllegalArgumentException(
				"Cell width must be a positive divisor of " + TerrainDensityJob.CHUNK_SIDE + ": " + cellWidth
			);
		}
		if (cellHeight <= 0 || cellHeight > height || height % cellHeight != 0) {
			throw new IllegalArgumentException("Cell height must be a positive divisor of generation height: " + cellHeight);
		}
		if (Math.floorMod(minY, cellHeight) != 0) {
			throw new IllegalArgumentException(
				"Minimum Y must align to the cell height: minY=" + minY + " cellHeight=" + cellHeight
			);
		}
		Math.addExact(minY, height);
	}

	public int sampleCount() {
		return Math.multiplyExact(TerrainDensityJob.CHUNK_SIDE * TerrainDensityJob.CHUNK_SIDE, height);
	}

	private static void validateIdentifierLength(Identifier identifier, String description) {
		int encodedBytes = identifier.toString().getBytes(StandardCharsets.UTF_8).length;
		if (encodedBytes > MAX_IDENTIFIER_UTF8_BYTES) {
			throw new IllegalArgumentException(
				description + " identifier exceeds " + MAX_IDENTIFIER_UTF8_BYTES + " UTF-8 bytes: " + encodedBytes
			);
		}
	}
}
