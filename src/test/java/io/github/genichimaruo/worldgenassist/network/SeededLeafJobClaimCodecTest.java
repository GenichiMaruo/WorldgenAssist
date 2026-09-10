package io.github.genichimaruo.worldgenassist.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import org.junit.jupiter.api.Test;

class SeededLeafJobClaimCodecTest {
	@Test
	void roundTripsTheExactFixedSizeClaim() {
		SeededLeafJobClaim claim = claim();
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SeededLeafJobClaimCodec.CODEC.encode(buffer, claim);
			assertEquals(SeededLeafJobClaimCodec.ENCODED_BYTES, buffer.readableBytes());
			assertEquals(claim, SeededLeafJobClaimCodec.CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes());
		} finally {
			buffer.release();
		}
	}

	@Test
	void rejectsUnknownVersionEveryTruncationAndTrailingData() {
		byte[] encoded = encode(claim());
		for (int length = 0; length < encoded.length; length++) {
			assertMalformed(Arrays.copyOf(encoded, length));
		}
		byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
		assertMalformed(trailing);
		encoded[0] = (byte)255;
		assertMalformed(encoded);
	}

	private static SeededLeafJobClaim claim() {
		return new SeededLeafJobClaim(
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			SeededLeafJobAuthenticationTag.fromHex("cd".repeat(SeededLeafJobAuthenticationTag.BYTE_LENGTH))
		);
	}

	private static byte[] encode(SeededLeafJobClaim claim) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SeededLeafJobClaimCodec.CODEC.encode(buffer, claim);
			byte[] bytes = new byte[buffer.readableBytes()];
			buffer.readBytes(bytes);
			return bytes;
		} finally {
			buffer.release();
		}
	}

	private static void assertMalformed(byte[] bytes) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
		try {
			assertThrows(RuntimeException.class, () -> SeededLeafJobClaimCodec.CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}
}
