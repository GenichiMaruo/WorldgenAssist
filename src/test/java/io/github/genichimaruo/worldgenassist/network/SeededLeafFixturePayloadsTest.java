package io.github.genichimaruo.worldgenassist.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import io.github.genichimaruo.worldgenassist.common.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeededLeafFixturePayloadsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void helloAndAcceptanceUseSeparateExactVersionedFrames() {
		check(SeededLeafFixturePayloads.Hello.CODEC, new SeededLeafFixturePayloads.Hello(3));
		// Unknown hello versions are decoded for an explicit manager-level rejection.
		check(SeededLeafFixturePayloads.Hello.CODEC, new SeededLeafFixturePayloads.Hello(2));
		check(SeededLeafFixturePayloads.Accepted.CODEC, new SeededLeafFixturePayloads.Accepted(true));
		check(SeededLeafFixturePayloads.Accepted.CODEC, new SeededLeafFixturePayloads.Accepted(false));
		malformed(SeededLeafFixturePayloads.Accepted.CODEC, new byte[] {0, 0, 0, 2, 1});
		malformed(SeededLeafFixturePayloads.Accepted.CODEC, new byte[] {0, 0, 0, 3, 2});
		assertEquals(new WorldgenProtocolVersion(2), WorldgenProtocolVersion.CURRENT);
	}
	@Test void requestResultFailureAndCancelRetainStrictBoundedDraftCodecs() {
		AuthorizedSeededLeafJob job = new AuthorizedSeededLeafJob(new SeededLeafJob(
			new UUID(0, 1), OpaqueWorldgenContextId.fromHex("ab".repeat(32)), Identifier.parse("minecraft:overworld"),
			10, -20, Identifier.parse("minecraft:overworld"), -64, 16, 4, 8,
			new SeededLeafTranscript(List.of(new SeededLeafTranscript.Entry(SeededLeafTranscript.Kind.NORMAL_NOISE,
				"normal:minecraft:temperature", 1, 2, 3, 4)))), SeededLeafJobAuthenticationTag.fromHex("cd".repeat(32)));
		SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(job);
		check(SeededLeafFixturePayloads.Request.CODEC, new SeededLeafFixturePayloads.Request(job));
		check(SeededLeafFixturePayloads.Failure.CODEC, new SeededLeafFixturePayloads.Failure(claim));
		check(SeededLeafFixturePayloads.Cancel.CODEC, new SeededLeafFixturePayloads.Cancel(claim));
		// Envelope payload validity is intentionally left to claim-before-decode server validation.
		check(SeededLeafFixturePayloads.Result.CODEC, new SeededLeafFixturePayloads.Result(
			new SeededLeafDensityResultEnvelope(claim, 4096, SeededLeafDensityResultEnvelope.Encoding.DEFLATE,
				new byte[] {1, 2, 3}, 0, 0)));
	}
	private static <T> void check(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		byte[] bytes;
		try {
			codec.encode(buffer, value);
			bytes = new byte[buffer.readableBytes()];
			buffer.getBytes(0, bytes);
			assertEquals(value, codec.decode(buffer));
			assertEquals(0, buffer.readableBytes());
		} finally { buffer.release(); }
		for (int length = 0; length < bytes.length; length++) { malformed(codec, Arrays.copyOf(bytes, length)); }
		malformed(codec, Arrays.copyOf(bytes, bytes.length + 1));
	}
	private static <T> void malformed(StreamCodec<RegistryFriendlyByteBuf, T> codec, byte[] bytes) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
		try { assertThrows(RuntimeException.class, () -> codec.decode(buffer)); } finally { buffer.release(); }
	}
}
