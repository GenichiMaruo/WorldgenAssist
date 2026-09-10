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

import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeededLeafJobCodecTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void roundTripsOpaqueIdentityGeometryAndTranscript() {
		SeededLeafJob job = job();
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SeededLeafJobCodec.CODEC.encode(buffer, job);
			assertTrue(buffer.readableBytes() <= SeededLeafJobCodec.MAX_ENCODED_BYTES);
			assertEquals(job, SeededLeafJobCodec.CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes());
		} finally {
			buffer.release();
		}
	}

	@Test
	void rejectsUnknownJobOrGraphVersionBeforeTranscript() {
		RegistryFriendlyByteBuf unknownJob = buffer();
		try {
			unknownJob.writeByte(255);
			assertThrows(IllegalArgumentException.class, () -> SeededLeafJobCodec.CODEC.decode(unknownJob));
		} finally {
			unknownJob.release();
		}

		RegistryFriendlyByteBuf unknownGraph = buffer();
		try {
			unknownGraph.writeByte(SeededLeafJob.FORMAT_VERSION);
			WorldgenPayloadCodecs.writeBoundedUtf8(
				unknownGraph,
				"minecraft:unknown/99",
				SeededLeafJob.MAX_IDENTIFIER_UTF8_BYTES,
				"Density graph ID"
			);
			assertThrows(IllegalArgumentException.class, () -> SeededLeafJobCodec.CODEC.decode(unknownGraph));
		} finally {
			unknownGraph.release();
		}
	}

	@Test
	void rejectsEveryTruncationOfAValidJob() {
		byte[] encoded = encode(job());
		for (int length = 0; length < encoded.length; length++) {
			RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
				Unpooled.wrappedBuffer(Arrays.copyOf(encoded, length)),
				RegistryAccess.EMPTY
			);
			try {
				assertThrows(RuntimeException.class, () -> SeededLeafJobCodec.CODEC.decode(buffer), "length=" + length);
			} finally {
				buffer.release();
			}
		}
	}

	private static SeededLeafJob job() {
		return new SeededLeafJob(
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
			new SeededLeafTranscript(List.of(
				new SeededLeafTranscript.Entry(
					SeededLeafTranscript.Kind.NORMAL_NOISE,
					"normal:minecraft:temperature",
					1L,
					2L,
					3L,
					4L
				),
				new SeededLeafTranscript.Entry(
					SeededLeafTranscript.Kind.BLENDED_NOISE,
					"blended:0",
					5L,
					6L,
					7L,
					8L
				)
			))
		);
	}

	private static byte[] encode(SeededLeafJob job) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SeededLeafJobCodec.CODEC.encode(buffer, job);
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
