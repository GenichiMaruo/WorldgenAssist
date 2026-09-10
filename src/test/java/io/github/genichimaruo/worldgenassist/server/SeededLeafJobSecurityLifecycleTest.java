package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobSpec;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeededLeafJobSecurityLifecycleTest {
	private static final UUID OWNER = UUID.fromString("16539a37-f40d-4b02-a378-8dc17c7b22db");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void lifecycleRotatesCancelsExpiresAndDropsLiveSecrets() {
		MutableNanoClock clock = new MutableNanoClock();
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
			2,
			2,
			10,
			10,
			Duration.ofNanos(100),
			Duration.ofNanos(50),
			clock,
			new SequentialJobIds(),
			new SequentialContexts(),
			new SequentialAuthenticators()
		);
		SeededLeafJobSecurityLifecycle lifecycle = new SeededLeafJobSecurityLifecycle(authority);

		lifecycle.onServerStarting();
		assertTrue(authority.isActive());
		AuthorizedSeededLeafJob disconnected = accepted(authority.tryIssue(OWNER, spec()));
		lifecycle.onDisconnect(OWNER);
		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.CANCELLED,
			authority.claimResponse(OWNER, SeededLeafJobClaim.fromAuthorization(disconnected))
		);

		AuthorizedSeededLeafJob reloaded = accepted(authority.tryIssue(OWNER, spec()));
		OpaqueWorldgenContextId oldContext = authority.contextId().orElseThrow();
		lifecycle.onDataPackReload();
		assertNotEquals(oldContext, authority.contextId().orElseThrow());
		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.CANCELLED,
			authority.claimResponse(OWNER, SeededLeafJobClaim.fromAuthorization(reloaded))
		);

		AuthorizedSeededLeafJob expiring = accepted(authority.tryIssue(OWNER, spec()));
		clock.advance(Duration.ofNanos(100));
		lifecycle.onEndServerTick();
		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.EXPIRED,
			authority.claimResponse(OWNER, SeededLeafJobClaim.fromAuthorization(expiring))
		);

		lifecycle.onServerStopping();
		assertFalse(authority.isActive());
		assertTrue(authority.contextId().isEmpty());
	}

	private static AuthorizedSeededLeafJob accepted(SeededLeafJobAuthority.IssueResult result) {
		assertEquals(SeededLeafJobAuthority.IssueStatus.ACCEPTED, result.status());
		return result.authorization().orElseThrow();
	}

	private static SeededLeafJobSpec spec() {
		return new SeededLeafJobSpec(
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
				"normal:minecraft:test",
				1L,
				2L,
				3L,
				4L
			)))
		);
	}

	private static byte[] filled(byte value) {
		byte[] bytes = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		Arrays.fill(bytes, value);
		return bytes;
	}

	private static final class MutableNanoClock implements java.util.function.LongSupplier {
		private long nanos;

		@Override
		public long getAsLong() {
			return nanos;
		}

		private void advance(Duration duration) {
			nanos += duration.toNanos();
		}
	}

	private static final class SequentialJobIds implements java.util.function.Supplier<UUID> {
		private long next = 1L;

		@Override
		public UUID get() {
			return new UUID(0L, next++);
		}
	}

	private static final class SequentialContexts implements java.util.function.Supplier<OpaqueWorldgenContextId> {
		private int next = 0xaa;

		@Override
		public OpaqueWorldgenContextId get() {
			return OpaqueWorldgenContextId.fromHex(Integer.toHexString(next++).repeat(OpaqueWorldgenContextId.BYTE_LENGTH));
		}
	}

	private static final class SequentialAuthenticators implements java.util.function.Supplier<SeededLeafJobAuthenticator> {
		private int next = 0x11;

		@Override
		public SeededLeafJobAuthenticator get() {
			return SeededLeafJobAuthenticator.fromKey(filled((byte)next++));
		}
	}
}
