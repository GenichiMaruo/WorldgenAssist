package io.github.genichimaruo.worldgenassist.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AuthorizedSeededLeafJobCodecTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void roundTripsTagAndJobWithinBound() {
		AuthorizedSeededLeafJob authorization = authorization();
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			AuthorizedSeededLeafJobCodec.CODEC.encode(buffer, authorization);
			assertTrue(buffer.readableBytes() <= AuthorizedSeededLeafJobCodec.MAX_ENCODED_BYTES);
			assertEquals(authorization, AuthorizedSeededLeafJobCodec.CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes());
		} finally {
			buffer.release();
		}
	}

	@Test
	void rejectsUnknownVersionAndEveryTruncation() {
		RegistryFriendlyByteBuf unknownVersion = buffer();
		try {
			unknownVersion.writeByte(255);
			assertThrows(IllegalArgumentException.class, () -> AuthorizedSeededLeafJobCodec.CODEC.decode(unknownVersion));
		} finally {
			unknownVersion.release();
		}

		byte[] encoded = encode(authorization());
		for (int length = 0; length < encoded.length; length++) {
			RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
				Unpooled.wrappedBuffer(Arrays.copyOf(encoded, length)),
				RegistryAccess.EMPTY
			);
			try {
				assertThrows(RuntimeException.class, () -> AuthorizedSeededLeafJobCodec.CODEC.decode(buffer), "length=" + length);
			} finally {
				buffer.release();
			}
		}
	}

	private static AuthorizedSeededLeafJob authorization() {
		SeededLeafJob job = new SeededLeafJob(
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			Identifier.parse("minecraft:overworld"),
			10,
			-20,
			Identifier.parse("minecraft:overworld"),
			-64,
			384,
			4,
			8,
			new SeededLeafTranscript(List.of(new SeededLeafTranscript.Entry(
				SeededLeafTranscript.Kind.NORMAL_NOISE,
				"normal:minecraft:temperature",
				1L,
				2L,
				3L,
				4L
			)))
		);
		return new AuthorizedSeededLeafJob(
			job,
			SeededLeafJobAuthenticationTag.fromHex("cd".repeat(SeededLeafJobAuthenticationTag.BYTE_LENGTH))
		);
	}

	private static byte[] encode(AuthorizedSeededLeafJob authorization) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			AuthorizedSeededLeafJobCodec.CODEC.encode(buffer, authorization);
			byte[] encoded = new byte[buffer.readableBytes()];
			buffer.readBytes(encoded);
			return encoded;
		} finally {
			buffer.release();
		}
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}
}
