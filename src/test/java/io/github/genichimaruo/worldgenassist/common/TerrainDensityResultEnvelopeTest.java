package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerrainDensityResultEnvelopeTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void losslesslyRoundTripsAndCompressesRepetitiveDensities() {
		double[] values = new double[TerrainDensityJob.MAX_SAMPLE_COUNT];
		for (int index = 0; index < values.length; index++) {
			values[index] = index % 32 == 0 ? -0.0 : 0.125;
		}
		TerrainDensityResult input = new TerrainDensityResult(identity(), values, 123L);

		TerrainDensityResultEnvelope envelope = TerrainDensityResultEnvelope.encode(input);
		TerrainDensityResult decoded = envelope.decode();

		assertEquals(TerrainDensityResultEnvelope.Encoding.DEFLATE, envelope.encoding());
		assertTrue(envelope.encodedDensityBytes() < envelope.rawDensityBytes());
		assertEquals(input, decoded);
	}

	@Test
	void rawOrCompressedIncompressibleBitsStillRoundTripExactly() {
		Random random = new Random(8675309L);
		double[] values = new double[4096];
		for (int index = 0; index < values.length; index++) {
			values[index] = (random.nextDouble() * 2.0 - 1.0) * TerrainDensityResult.MAX_ABSOLUTE_DENSITY;
		}
		TerrainDensityResult input = new TerrainDensityResult(identity(), values, 0L);

		TerrainDensityResultEnvelope envelope = TerrainDensityResultEnvelope.encode(input);

		assertEquals(input, envelope.decode());
		assertTrue(envelope.encodedDensityBytes() <= envelope.rawDensityBytes());
	}

	@Test
	void rejectsCorruptionTruncationTrailingDataAndExpansionPastDeclaredCount() {
		double[] values = new double[100];
		TerrainDensityResultEnvelope valid = TerrainDensityResultEnvelope.encode(
			new TerrainDensityResult(identity(), values, 0L)
		);
		assertEquals(TerrainDensityResultEnvelope.Encoding.DEFLATE, valid.encoding());

		byte[] corrupted = valid.encodedDensities();
		corrupted[corrupted.length / 2] ^= 0x40;
		assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.densityCount(), corrupted).decode());

		byte[] truncated = Arrays.copyOf(valid.encodedDensities(), valid.encodedDensityBytes() - 1);
		assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.densityCount(), truncated).decode());

		byte[] trailing = Arrays.copyOf(valid.encodedDensities(), valid.encodedDensityBytes() + 1);
		assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.densityCount(), trailing).decode());

		assertThrows(IllegalArgumentException.class, () -> copy(valid, 2, valid.encodedDensities()).decode());
	}

	@Test
	void defensivelyCopiesEncodedBytesAndValidatesWireBounds() {
		byte[] raw = new byte[Double.BYTES];
		TerrainDensityResultEnvelope envelope = new TerrainDensityResultEnvelope(
			identity(),
			1,
			TerrainDensityResultEnvelope.Encoding.RAW,
			raw,
			0L,
			0L
		);
		raw[0] = 1;
		byte[] exposed = envelope.encodedDensities();
		exposed[1] = 1;

		assertArrayEquals(new byte[Double.BYTES], envelope.encodedDensities());
		assertThrows(
			IllegalArgumentException.class,
			() -> new TerrainDensityResultEnvelope(identity(), 1, TerrainDensityResultEnvelope.Encoding.RAW, new byte[7], 0L, 0L)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new TerrainDensityResultEnvelope(identity(), 1, TerrainDensityResultEnvelope.Encoding.DEFLATE, new byte[9], 0L, 0L)
		);
	}

	private static TerrainDensityResultEnvelope copy(TerrainDensityResultEnvelope source, int densityCount, byte[] bytes) {
		return new TerrainDensityResultEnvelope(
			source.identity(),
			densityCount,
			source.encoding(),
			bytes,
			source.clientComputeNanos(),
			source.clientEncodeNanos()
		);
	}

	private static TerrainJobIdentity identity() {
		return new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			Identifier.parse("minecraft:overworld"),
			10,
			-20,
			WorldgenContextFingerprint.fromHex("ab".repeat(32))
		);
	}
}
