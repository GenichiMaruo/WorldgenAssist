package io.github.genichimaruo.worldgenassist.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import org.junit.jupiter.api.Test;

class SeededLeafDensityResultEnvelopeCodecTest {
	@Test
	void roundTripsWithoutRegisteringAPayload() {
		SeededLeafDensityResultEnvelope envelope = SeededLeafDensityResultEnvelope.encode(
			new SeededLeafDensityResult(claim(), new double[] {0.25, -0.5, -0.0}, 123L)
		);
		byte[] encoded = encode(envelope);

		assertEquals(envelope, decode(encoded));
	}

	@Test
	void rejectsEveryTruncationTrailingDataAndUnknownVersions() {
		byte[] encoded = encode(SeededLeafDensityResultEnvelope.encode(
			new SeededLeafDensityResult(claim(), new double[] {0.25, -0.5}, 123L)
		));
		for (int length = 0; length < encoded.length; length++) {
			assertMalformed(Arrays.copyOf(encoded, length));
		}
		assertMalformed(Arrays.copyOf(encoded, encoded.length + 1));

		byte[] unknownEnvelope = encoded.clone();
		unknownEnvelope[0] = (byte)255;
		assertMalformed(unknownEnvelope);
		byte[] unknownClaim = encoded.clone();
		unknownClaim[1] = (byte)255;
		assertMalformed(unknownClaim);
	}

	@Test
	void rejectsPayloadBeyondTheStandaloneBoundBeforeFieldsAreRead() {
		assertMalformed(new byte[SeededLeafDensityResultEnvelopeCodec.MAX_ENCODED_BYTES + 1]);
	}

	@Test
	void acceptsTheMaximumRawDensitySectionWithinItsDeclaredBound() {
		SeededLeafDensityResultEnvelope envelope = new SeededLeafDensityResultEnvelope(
			claim(),
			TerrainDensityJob.MAX_SAMPLE_COUNT,
			SeededLeafDensityResultEnvelope.Encoding.RAW,
			new byte[SeededLeafDensityResultEnvelope.MAX_ENCODED_DENSITY_BYTES],
			SeededLeafDensityResult.MAX_CLIENT_COMPUTE_NANOS,
			SeededLeafDensityResult.MAX_CLIENT_COMPUTE_NANOS
		);
		byte[] encoded = encode(envelope);

		assertTrue(encoded.length <= SeededLeafDensityResultEnvelopeCodec.MAX_ENCODED_BYTES);
		assertEquals(envelope, decode(encoded));
	}

	private static SeededLeafJobClaim claim() {
		return new SeededLeafJobClaim(
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			SeededLeafJobAuthenticationTag.fromHex("cd".repeat(SeededLeafJobAuthenticationTag.BYTE_LENGTH))
		);
	}

	private static byte[] encode(SeededLeafDensityResultEnvelope envelope) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SeededLeafDensityResultEnvelopeCodec.CODEC.encode(buffer, envelope);
			byte[] bytes = new byte[buffer.readableBytes()];
			buffer.readBytes(bytes);
			return bytes;
		} finally {
			buffer.release();
		}
	}

	private static SeededLeafDensityResultEnvelope decode(byte[] bytes) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
		try {
			return SeededLeafDensityResultEnvelopeCodec.CODEC.decode(buffer);
		} finally {
			buffer.release();
		}
	}

	private static void assertMalformed(byte[] bytes) {
		assertThrows(RuntimeException.class, () -> decode(bytes));
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}
}
