package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SeededLeafDensityResultEnvelopeTest {
	@Test
	void resultIsSeedFreeDefensiveAndValueBounded() {
		double[] values = {0.25, -0.0};
		SeededLeafDensityResult result = new SeededLeafDensityResult(claim(), values, 123L);
		values[0] = 9.0;
		double[] exposed = result.densities();
		exposed[1] = 9.0;

		assertArrayEquals(new double[] {0.25, -0.0}, result.densities());
		assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(result.densityAt(1)));
		assertFalse(Arrays.stream(SeededLeafDensityResult.class.getDeclaredFields())
			.map(java.lang.reflect.Field::getName)
			.anyMatch(name -> name.equals("worldSeed") || name.equals("contextFingerprint")));
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafDensityResult(claim(), new double[0], 0L));
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafDensityResult(claim(), new double[] {Double.NaN}, 0L));
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafDensityResult(claim(), new double[] {Double.POSITIVE_INFINITY}, 0L));
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafDensityResult(
			claim(),
			new double[] {SeededLeafDensityResult.MAX_ABSOLUTE_DENSITY + 1.0},
			0L
		));
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafDensityResult(claim(), new double[] {0.0}, -1L));
	}

	@Test
	void losslesslyRoundTripsCompressedAndRawValues() {
		double[] repetitive = new double[TerrainDensityJob.MAX_SAMPLE_COUNT];
		for (int index = 0; index < repetitive.length; index++) {
			repetitive[index] = index % 32 == 0 ? -0.0 : 0.125;
		}
		SeededLeafDensityResult compressedInput = new SeededLeafDensityResult(claim(), repetitive, 123L);
		SeededLeafDensityResultEnvelope compressed = SeededLeafDensityResultEnvelope.encode(compressedInput);
		assertEquals(SeededLeafDensityResultEnvelope.Encoding.DEFLATE, compressed.encoding());
		assertTrue(compressed.encodedDensityBytes() < compressed.rawDensityBytes());
		assertEquals(compressedInput, compressed.decode());

		Random random = new Random(8675309L);
		double[] varied = new double[4_096];
		for (int index = 0; index < varied.length; index++) {
			varied[index] = (random.nextDouble() * 2.0 - 1.0) * SeededLeafDensityResult.MAX_ABSOLUTE_DENSITY;
		}
		SeededLeafDensityResult rawInput = new SeededLeafDensityResult(claim(), varied, 0L);
		SeededLeafDensityResultEnvelope raw = SeededLeafDensityResultEnvelope.encode(rawInput);
		assertEquals(rawInput, raw.decode());
		assertTrue(raw.encodedDensityBytes() <= raw.rawDensityBytes());
	}

	@Test
	void rejectsMalformedStreamsAndInvalidDecodedValues() {
		SeededLeafDensityResultEnvelope valid = SeededLeafDensityResultEnvelope.encode(
			new SeededLeafDensityResult(claim(), new double[100], 0L)
		);
		byte[] corrupted = valid.encodedDensities();
		corrupted[corrupted.length / 2] ^= 0x40;
		assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.densityCount(), corrupted).decode());

		byte[] truncated = Arrays.copyOf(valid.encodedDensities(), valid.encodedDensityBytes() - 1);
		assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.densityCount(), truncated).decode());
		byte[] trailing = Arrays.copyOf(valid.encodedDensities(), valid.encodedDensityBytes() + 1);
		assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.densityCount(), trailing).decode());
		assertThrows(IllegalArgumentException.class, () -> copy(valid, 2, valid.encodedDensities()).decode());

		byte[] nan = ByteBuffer.allocate(Double.BYTES).order(ByteOrder.BIG_ENDIAN).putDouble(Double.NaN).array();
		SeededLeafDensityResultEnvelope invalidValue = new SeededLeafDensityResultEnvelope(
			claim(), 1, SeededLeafDensityResultEnvelope.Encoding.RAW, nan, 0L, 0L
		);
		assertThrows(IllegalArgumentException.class, invalidValue::decode);
	}

	@Test
	void defensivelyCopiesEncodedBytesAndValidatesWireBounds() {
		byte[] raw = new byte[Double.BYTES];
		SeededLeafDensityResultEnvelope envelope = new SeededLeafDensityResultEnvelope(
			claim(), 1, SeededLeafDensityResultEnvelope.Encoding.RAW, raw, 0L, 0L
		);
		raw[0] = 1;
		byte[] exposed = envelope.encodedDensities();
		exposed[1] = 1;

		assertArrayEquals(new byte[Double.BYTES], envelope.encodedDensities());
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafDensityResultEnvelope(
			claim(), 1, SeededLeafDensityResultEnvelope.Encoding.RAW, new byte[7], 0L, 0L
		));
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafDensityResultEnvelope(
			claim(), 1, SeededLeafDensityResultEnvelope.Encoding.DEFLATE, new byte[9], 0L, 0L
		));
	}

	private static SeededLeafDensityResultEnvelope copy(
		SeededLeafDensityResultEnvelope source,
		int densityCount,
		byte[] bytes
	) {
		return new SeededLeafDensityResultEnvelope(
			source.claim(),
			densityCount,
			source.encoding(),
			bytes,
			source.clientComputeNanos(),
			source.clientEncodeNanos()
		);
	}

	private static SeededLeafJobClaim claim() {
		return new SeededLeafJobClaim(
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			SeededLeafJobAuthenticationTag.fromHex("cd".repeat(SeededLeafJobAuthenticationTag.BYTE_LENGTH))
		);
	}
}
