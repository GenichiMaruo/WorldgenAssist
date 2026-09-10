package io.github.genichimaruo.worldgenassist.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldgenPayloadCodecTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void roundTripsEveryPayload() {
		WorkerHelloPayload hello = new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 1, "0.1.0");
		assertEquals(hello, roundTrip(hello, WorkerHelloPayload.CODEC));

		WorkerAcceptedPayload accepted = new WorkerAcceptedPayload(
			WorldgenProtocolVersion.CURRENT,
			WorkerAcceptedPayload.Status.ACCEPTED,
			1
		);
		assertEquals(accepted, roundTrip(accepted, WorkerAcceptedPayload.CODEC));

		TerrainDensityJob job = job();
		assertEquals(new TerrainJobRequestPayload(job), roundTrip(new TerrainJobRequestPayload(job), TerrainJobRequestPayload.CODEC));

		TerrainDensityResult result = new TerrainDensityResult(job.identity(), new double[] {0.25, -0.5}, 123L);
		TerrainDensityResultEnvelope envelope = TerrainDensityResultEnvelope.encode(result);
		assertEquals(new TerrainJobResultPayload(envelope), roundTrip(new TerrainJobResultPayload(envelope), TerrainJobResultPayload.CODEC));
		TerrainJobFailurePayload failure = new TerrainJobFailurePayload(job.identity(), TerrainJobFailurePayload.Reason.BUSY);
		assertEquals(failure, roundTrip(failure, TerrainJobFailurePayload.CODEC));

		TerrainJobCancelPayload cancel = new TerrainJobCancelPayload(job.identity());
		assertEquals(cancel, roundTrip(cancel, TerrainJobCancelPayload.CODEC));
	}

	@Test
	void rejectsOversizedDensityCountBeforeAllocation() {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			WorldgenPayloadCodecs.writeIdentity(buffer, identity());
			buffer.writeVarInt(TerrainDensityJob.MAX_SAMPLE_COUNT + 1);
			assertThrows(IllegalArgumentException.class, () -> TerrainJobResultPayload.CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	@Test
	void rejectsUnknownEncodingAndEncodedLengthBeforeAllocation() {
		RegistryFriendlyByteBuf unknownEncoding = buffer();
		try {
			WorldgenPayloadCodecs.writeIdentity(unknownEncoding, identity());
			unknownEncoding.writeVarInt(1);
			unknownEncoding.writeByte(255);
			assertThrows(IllegalArgumentException.class, () -> TerrainJobResultPayload.CODEC.decode(unknownEncoding));
		} finally {
			unknownEncoding.release();
		}

		RegistryFriendlyByteBuf oversized = buffer();
		try {
			WorldgenPayloadCodecs.writeIdentity(oversized, identity());
			oversized.writeVarInt(1);
			oversized.writeByte(TerrainDensityResultEnvelope.Encoding.DEFLATE.ordinal());
			oversized.writeVarInt(Double.BYTES + 1);
			assertThrows(IllegalArgumentException.class, () -> TerrainJobResultPayload.CODEC.decode(oversized));
		} finally {
			oversized.release();
		}
	}

	@Test
	void maximumResultFitsTheRegisteredLargePayloadBound() {
		double[] values = new double[TerrainDensityJob.MAX_SAMPLE_COUNT];
		TerrainJobResultPayload payload = new TerrainJobResultPayload(
			TerrainDensityResultEnvelope.encode(new TerrainDensityResult(identity(), values, 0L))
		);
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			TerrainJobResultPayload.CODEC.encode(buffer, payload);
			assertTrue(buffer.readableBytes() <= WorldgenPayloadTypes.MAX_DENSITY_RESULT_PAYLOAD_BYTES);
			assertEquals(payload, TerrainJobResultPayload.CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	@Test
	void rejectsUnknownHandshakeStatusAndInvalidHelloBounds() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, WorkerHelloPayload.MAX_PARALLEL_JOBS + 1, "test")
		);

		RegistryFriendlyByteBuf buffer = buffer();
		try {
			buffer.writeVarInt(WorldgenProtocolVersion.CURRENT.value());
			buffer.writeByte(255);
			buffer.writeVarInt(0);
			assertThrows(IllegalArgumentException.class, () -> WorkerAcceptedPayload.CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	@Test
	void boundedRandomResultPayloadFuzzingNeverEscapesWithAnError() {
		assertDoesNotThrow(() -> {
			Random random = new Random(0xCA7_0002L);
			for (int iteration = 0; iteration < 2_000; iteration++) {
				byte[] bytes = new byte[random.nextInt(513)];
				random.nextBytes(bytes);
				RegistryFriendlyByteBuf buffer = buffer();
				try {
					buffer.writeBytes(bytes);
					try {
						TerrainJobResultPayload decoded = TerrainJobResultPayload.CODEC.decode(buffer);
						assertTrue(decoded.result().densityCount() <= TerrainDensityJob.MAX_SAMPLE_COUNT);
						assertTrue(decoded.result().encodedDensityBytes() <= decoded.result().rawDensityBytes());
					} catch (RuntimeException expectedMalformedInput) {
						// Malformed random input may fail at any bounded identity/envelope field.
					}
				} finally {
					buffer.release();
				}
			}
		});
	}

	private static <T> T roundTrip(T value, net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> codec) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			codec.encode(buffer, value);
			T decoded = codec.decode(buffer);
			assertEquals(0, buffer.readableBytes());
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	private static TerrainDensityJob job() {
		return new TerrainDensityJob(
			identity(),
			8675309L,
			true,
			Identifier.parse("minecraft:overworld"),
			-64,
			384,
			4,
			8
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
