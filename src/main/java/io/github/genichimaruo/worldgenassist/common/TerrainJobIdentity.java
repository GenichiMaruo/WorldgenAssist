package io.github.genichimaruo.worldgenassist.common;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

public record TerrainJobIdentity(
	WorldgenProtocolVersion protocolVersion,
	UUID jobId,
	Identifier dimension,
	int chunkX,
	int chunkZ,
	WorldgenContextFingerprint contextFingerprint
) {
	public static final int MAX_DIMENSION_ID_UTF8_BYTES = 256;

	public TerrainJobIdentity {
		Objects.requireNonNull(protocolVersion, "protocolVersion").requireSupported();
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(contextFingerprint, "contextFingerprint");

		if (jobId.getMostSignificantBits() == 0L && jobId.getLeastSignificantBits() == 0L) {
			throw new IllegalArgumentException("Job ID must not be the all-zero UUID");
		}
		if (dimension.getNamespace().isEmpty() || dimension.getPath().isEmpty()) {
			throw new IllegalArgumentException("Dimension identifier namespace and path must both be non-empty");
		}
		int dimensionBytes = dimension.toString().getBytes(StandardCharsets.UTF_8).length;
		if (dimensionBytes > MAX_DIMENSION_ID_UTF8_BYTES) {
			throw new IllegalArgumentException(
				"Dimension identifier exceeds " + MAX_DIMENSION_ID_UTF8_BYTES + " UTF-8 bytes: " + dimensionBytes
			);
		}
		if (!ChunkPos.isValid(chunkX, chunkZ)) {
			throw new IllegalArgumentException("Chunk coordinate is outside Minecraft's valid generation bounds: " + chunkX + "," + chunkZ);
		}
	}

	public ChunkPos chunkPos() {
		return new ChunkPos(chunkX, chunkZ);
	}
}
