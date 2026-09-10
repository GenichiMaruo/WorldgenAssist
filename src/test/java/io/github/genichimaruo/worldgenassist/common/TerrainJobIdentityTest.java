package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkPyramid;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerrainJobIdentityTest {
	private static final UUID JOB_ID = UUID.fromString("b47d83d4-6cba-45ab-8819-62163341f4ad");
	private static final WorldgenContextFingerprint CONTEXT = WorldgenContextFingerprint.fromBytes(
		new byte[WorldgenContextFingerprint.BYTE_LENGTH]
	);

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void retainsCanonicalIdentityAndBuildsChunkPosition() {
		TerrainJobIdentity identity = new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			JOB_ID,
			Identifier.parse("minecraft:overworld"),
			12,
			-34,
			CONTEXT
		);

		assertEquals(WorldgenProtocolVersion.CURRENT, identity.protocolVersion());
		assertEquals(JOB_ID, identity.jobId());
		assertEquals(Identifier.parse("minecraft:overworld"), identity.dimension());
		assertEquals(new ChunkPos(12, -34), identity.chunkPos());
		assertEquals(CONTEXT, identity.contextFingerprint());
	}

	@Test
	void acceptsMinecraftsInclusiveChunkCoordinateBoundary() {
		int boundary = ChunkPyramid.MAX_CHUNK_COORDINATE_VALUE;

		new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			JOB_ID,
			Identifier.parse("minecraft:overworld"),
			boundary,
			-boundary,
			CONTEXT
		);
	}

	@Test
	void rejectsUnsupportedVersionAndSentinelJobId() {
		assertThrows(
			IllegalArgumentException.class,
			() -> identity(new WorldgenProtocolVersion(3), JOB_ID, Identifier.parse("minecraft:overworld"), 0, 0)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> identity(WorldgenProtocolVersion.CURRENT, new UUID(0L, 0L), Identifier.parse("minecraft:overworld"), 0, 0)
		);
	}

	@Test
	void rejectsChunkCoordinatesOutsideMinecraftsGenerationBoundary() {
		int outside = ChunkPyramid.MAX_CHUNK_COORDINATE_VALUE + 1;

		assertThrows(
			IllegalArgumentException.class,
			() -> identity(WorldgenProtocolVersion.CURRENT, JOB_ID, Identifier.parse("minecraft:overworld"), outside, 0)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> identity(WorldgenProtocolVersion.CURRENT, JOB_ID, Identifier.parse("minecraft:overworld"), 0, -outside)
		);
	}

	@Test
	void boundsCanonicalDimensionIdentifierBytes() {
		Identifier atLimit = Identifier.fromNamespaceAndPath("test", "a".repeat(251));
		Identifier overLimit = Identifier.fromNamespaceAndPath("test", "a".repeat(252));

		new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, JOB_ID, atLimit, 0, 0, CONTEXT);
		assertThrows(
			IllegalArgumentException.class,
			() -> new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, JOB_ID, overLimit, 0, 0, CONTEXT)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, JOB_ID, Identifier.parse("minecraft:"), 0, 0, CONTEXT)
		);
	}

	private static TerrainJobIdentity identity(
		WorldgenProtocolVersion protocolVersion,
		UUID jobId,
		Identifier dimension,
		int chunkX,
		int chunkZ
	) {
		return new TerrainJobIdentity(protocolVersion, jobId, dimension, chunkX, chunkZ, CONTEXT);
	}
}
