package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobSpec;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeededLeafJobAuthorityTest {
	private static final UUID OWNER_A = UUID.fromString("16539a37-f40d-4b02-a378-8dc17c7b22db");
	private static final UUID OWNER_B = UUID.fromString("4f4b52e7-c70d-42aa-93de-a14fc1145153");
	private static final UUID OWNER_C = UUID.fromString("fa8d2b6f-20f3-4361-ae56-81cb3b9a4d4f");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void serverLifetimeIssuesAnExactClaimOnceAndDropsSecretsOnStop() {
		SeededLeafJobAuthority authority = authority(2, 2, 10, new MutableNanoClock(), new SequentialJobIds());
		assertEquals(SeededLeafJobAuthority.IssueStatus.SERVER_INACTIVE, authority.tryIssue(OWNER_A, spec(1)).status());

		OpaqueWorldgenContextId firstContext = authority.start();
		assertTrue(authority.isActive());
		assertEquals(firstContext, authority.contextId().orElseThrow());
		assertEquals(1L, authority.contextGeneration());
		AuthorizedSeededLeafJob authorization = accepted(authority.tryIssue(OWNER_A, spec(1)));
		SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(authorization);

		assertEquals(SeededLeafJobAuthority.ClaimStatus.ACCEPTED, authority.claimResponse(OWNER_A, claim));
		assertEquals(SeededLeafJobAuthority.ClaimStatus.DUPLICATE, authority.claimResponse(OWNER_A, claim));
		assertTrue(authority.stop().isEmpty());
		assertFalse(authority.isActive());
		assertTrue(authority.contextId().isEmpty());
		assertEquals(SeededLeafJobAuthority.ClaimStatus.SERVER_INACTIVE, authority.claimResponse(OWNER_A, claim));

		OpaqueWorldgenContextId secondContext = authority.start();
		assertNotEquals(firstContext, secondContext);
		assertEquals(2L, authority.contextGeneration());
		assertEquals(0, authority.disclosedEntriesFor(OWNER_A));
	}

	@Test
	void ownerContextAndAuthenticationMismatchDoNotConsumeTheClaim() {
		SeededLeafJobAuthority authority = authority(2, 2, 10, new MutableNanoClock(), new SequentialJobIds());
		authority.start();
		AuthorizedSeededLeafJob authorization = accepted(authority.tryIssue(OWNER_A, spec(1)));
		SeededLeafJobClaim exact = SeededLeafJobClaim.fromAuthorization(authorization);

		assertEquals(SeededLeafJobAuthority.ClaimStatus.OWNER_MISMATCH, authority.claimResponse(OWNER_B, exact));
		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.CONTEXT_MISMATCH,
			authority.claimResponse(OWNER_A, new SeededLeafJobClaim(
				exact.jobId(),
				OpaqueWorldgenContextId.fromHex("cc".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
				exact.authenticationTag()
			))
		);
		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.AUTHENTICATION_FAILED,
			authority.claimResponse(OWNER_A, new SeededLeafJobClaim(
				exact.jobId(),
				exact.contextId(),
				SeededLeafJobAuthenticationTag.fromHex("dd".repeat(SeededLeafJobAuthenticationTag.BYTE_LENGTH))
			))
		);
		assertEquals(1, authority.pendingCount());
		assertEquals(SeededLeafJobAuthority.ClaimStatus.ACCEPTED, authority.claimResponse(OWNER_A, exact));
	}

	@Test
	void enforcesInFlightAndNonRefundablePerOwnerDisclosureLimits() {
		SeededLeafJobAuthority authority = authority(2, 1, 3, new MutableNanoClock(), new SequentialJobIds());
		authority.start();
		AuthorizedSeededLeafJob ownerA = accepted(authority.tryIssue(OWNER_A, spec(2)));
		assertEquals(SeededLeafJobAuthority.IssueStatus.OWNER_LIMIT_REACHED, authority.tryIssue(OWNER_A, spec(1)).status());
		AuthorizedSeededLeafJob ownerB = accepted(authority.tryIssue(OWNER_B, spec(1)));
		assertEquals(SeededLeafJobAuthority.IssueStatus.GLOBAL_LIMIT_REACHED, authority.tryIssue(OWNER_C, spec(1)).status());

		assertEquals(SeededLeafJobAuthority.CancellationStatus.CANCELLED, authority.cancel(OWNER_A, ownerA.job().jobId()));
		assertEquals(1, authority.pendingTranscriptEntries());
		assertEquals(2, authority.disclosedEntriesFor(OWNER_A));
		assertEquals(
			SeededLeafJobAuthority.IssueStatus.OWNER_DISCLOSURE_LIMIT_REACHED,
			authority.tryIssue(OWNER_A, spec(2)).status()
		);
		assertEquals(2, authority.disclosedEntriesFor(OWNER_A));
		assertEquals(List.of(ownerB), authority.cancelAllForOwner(OWNER_B));
		assertEquals(0, authority.pendingTranscriptEntries());
		assertEquals(1, authority.disclosedEntriesFor(OWNER_B));
	}

	@Test
	void boundsAggregatePendingTranscriptMemoryAndReleasesItAtTerminalState() {
		MutableNanoClock clock = new MutableNanoClock();
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
			3,
			2,
			2,
			10,
			Duration.ofNanos(100),
			Duration.ofNanos(50),
			clock,
			new SequentialJobIds(),
			new SequentialContexts(),
			new SequentialAuthenticators()
		);
		authority.start();
		AuthorizedSeededLeafJob first = accepted(authority.tryIssue(OWNER_A, spec(2)));
		assertEquals(2, authority.pendingTranscriptEntries());
		assertEquals(
			SeededLeafJobAuthority.IssueStatus.GLOBAL_TRANSCRIPT_LIMIT_REACHED,
			authority.tryIssue(OWNER_B, spec(1)).status()
		);

		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.ACCEPTED,
			authority.claimResponse(OWNER_A, SeededLeafJobClaim.fromAuthorization(first))
		);
		assertEquals(0, authority.pendingTranscriptEntries());
		accepted(authority.tryIssue(OWNER_B, spec(1)));
	}

	@Test
	void boundsRetainedDisclosureOwnerStateAcrossSequentialConnections() {
		SeededLeafJobAuthority authority = authority(
			1,
			1,
			1,
			new MutableNanoClock(),
			new SequentialJobIds()
		);
		authority.start();
		for (int index = 0; index < SeededLeafJobAuthority.MAX_TRACKED_DISCLOSURE_OWNERS; index++) {
			UUID ownerId = new UUID(1L, index + 1L);
			AuthorizedSeededLeafJob authorization = accepted(authority.tryIssue(ownerId, spec(1)));
			assertEquals(
				SeededLeafJobAuthority.ClaimStatus.ACCEPTED,
				authority.claimResponse(ownerId, SeededLeafJobClaim.fromAuthorization(authorization))
			);
		}

		assertEquals(
			SeededLeafJobAuthority.IssueStatus.GLOBAL_DISCLOSURE_OWNER_LIMIT_REACHED,
			authority.tryIssue(new UUID(2L, 1L), spec(1)).status()
		);
	}

	@Test
	void reloadCancelsOldContextRotatesKeyAndRetainsDisclosureCounters() {
		SeededLeafJobAuthority authority = authority(3, 2, 3, new MutableNanoClock(), new SequentialJobIds());
		OpaqueWorldgenContextId firstContext = authority.start();
		AuthorizedSeededLeafJob first = accepted(authority.tryIssue(OWNER_A, spec(2)));
		AuthorizedSeededLeafJob second = accepted(authority.tryIssue(OWNER_B, spec(1)));

		assertEquals(List.of(first, second), authority.reload());
		OpaqueWorldgenContextId secondContext = authority.contextId().orElseThrow();
		assertNotEquals(firstContext, secondContext);
		assertEquals(2L, authority.contextGeneration());
		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.CANCELLED,
			authority.claimResponse(OWNER_A, SeededLeafJobClaim.fromAuthorization(first))
		);
		assertEquals(2, authority.disclosedEntriesFor(OWNER_A));
		assertEquals(
			SeededLeafJobAuthority.IssueStatus.OWNER_DISCLOSURE_LIMIT_REACHED,
			authority.tryIssue(OWNER_A, spec(2)).status()
		);

		AuthorizedSeededLeafJob fresh = accepted(authority.tryIssue(OWNER_C, spec(1)));
		assertEquals(secondContext, fresh.job().contextId());
		assertNotEquals(first.authenticationTag(), fresh.authenticationTag());
	}

	@Test
	void expirationCancellationAndTerminalRetentionFailClosed() {
		MutableNanoClock clock = new MutableNanoClock();
		SeededLeafJobAuthority authority = authority(2, 2, 10, clock, new SequentialJobIds());
		authority.start();
		AuthorizedSeededLeafJob expiring = accepted(authority.tryIssue(OWNER_A, spec(1)));
		SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(expiring);

		clock.advance(Duration.ofNanos(99));
		assertTrue(authority.expireTimedOut().isEmpty());
		clock.advance(Duration.ofNanos(1));
		assertEquals(List.of(expiring), authority.expireTimedOut());
		assertEquals(SeededLeafJobAuthority.ClaimStatus.EXPIRED, authority.claimResponse(OWNER_A, claim));
		assertEquals(SeededLeafJobAuthority.CancellationStatus.EXPIRED, authority.cancel(OWNER_A, claim.jobId()));

		clock.advance(Duration.ofNanos(50));
		assertEquals(SeededLeafJobAuthority.ClaimStatus.UNKNOWN_JOB, authority.claimResponse(OWNER_A, claim));
	}

	@Test
	void concurrentClaimsHaveExactlyOneWinner() throws Exception {
		SeededLeafJobAuthority authority = authority(2, 2, 10, new MutableNanoClock(), new SequentialJobIds());
		authority.start();
		SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(accepted(authority.tryIssue(OWNER_A, spec(1))));
		List<Callable<SeededLeafJobAuthority.ClaimStatus>> attempts = new ArrayList<>();
		for (int index = 0; index < 8; index++) {
			attempts.add(() -> authority.claimResponse(OWNER_A, claim));
		}

		List<SeededLeafJobAuthority.ClaimStatus> statuses;
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			statuses = executor.invokeAll(attempts).stream().map(future -> {
				try {
					return future.get();
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
			}).toList();
		}

		assertEquals(1, statuses.stream().filter(status -> status == SeededLeafJobAuthority.ClaimStatus.ACCEPTED).count());
		assertEquals(7, statuses.stream().filter(status -> status == SeededLeafJobAuthority.ClaimStatus.DUPLICATE).count());
		assertEquals(0, authority.pendingCount());
	}

	@Test
	void jobIdAllocationSkipsZeroAndTrackedCollisions() {
		UUID firstId = new UUID(1L, 1L);
		UUID secondId = new UUID(2L, 2L);
		Queue<UUID> ids = new ArrayDeque<>(Arrays.asList(new UUID(0L, 0L), firstId, firstId, secondId));
		SeededLeafJobAuthority authority = authority(2, 2, 10, new MutableNanoClock(), ids::remove);
		authority.start();

		assertEquals(firstId, accepted(authority.tryIssue(OWNER_A, spec(1))).job().jobId());
		assertEquals(secondId, accepted(authority.tryIssue(OWNER_B, spec(1))).job().jobId());
	}

	@Test
	void validatesResourceBoundsAndLifecycleTransitions() {
		MutableNanoClock clock = new MutableNanoClock();
		SequentialJobIds ids = new SequentialJobIds();
		Supplier<OpaqueWorldgenContextId> contexts = new SequentialContexts();
		Supplier<SeededLeafJobAuthenticator> authenticators = new SequentialAuthenticators();

		assertThrows(IllegalArgumentException.class, () -> authority(0, 1, 1, clock, ids));
		assertThrows(IllegalArgumentException.class, () -> authority(1, 2, 1, clock, ids));
		assertThrows(IllegalArgumentException.class, () -> authority(1, 1, 0, clock, ids));
		assertThrows(
			IllegalArgumentException.class,
			() -> new SeededLeafJobAuthority(1, 1, 0, 1, Duration.ofNanos(1), Duration.ofNanos(1), clock, ids, contexts, authenticators)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new SeededLeafJobAuthority(1, 1, 1, 1, Duration.ZERO, Duration.ofNanos(1), clock, ids, contexts, authenticators)
		);

		SeededLeafJobAuthority authority = authority(1, 1, 1, clock, ids);
		assertThrows(IllegalStateException.class, authority::reload);
		authority.start();
		assertThrows(IllegalStateException.class, authority::start);
	}

	@Test
	void chargesInjectedGlobalBudgetBeforeIssuanceAndNeverRefundsTerminalTransitions() {
		MutableNanoClock clock = new MutableNanoClock();
		DeterministicBudget budget = new DeterministicBudget(5);
		SeededLeafJobAuthority authority = authorityWithBudget(3, 2, 10, clock, budget);
		authority.start();

		AuthorizedSeededLeafJob cancelled = accepted(authority.tryIssue(OWNER_A, spec(2)));
		assertEquals(2L, budget.snapshot().usedEntries());
		assertEquals(SeededLeafJobAuthority.CancellationStatus.CANCELLED, authority.cancel(OWNER_A, cancelled.job().jobId()));
		assertEquals(2L, budget.snapshot().usedEntries());

		AuthorizedSeededLeafJob claimed = accepted(authority.tryIssue(OWNER_B, spec(1)));
		assertEquals(SeededLeafJobAuthority.ClaimStatus.ACCEPTED,
			authority.claimResponse(OWNER_B, SeededLeafJobClaim.fromAuthorization(claimed)));
		assertEquals(3L, budget.snapshot().usedEntries());

		AuthorizedSeededLeafJob reloaded = accepted(authority.tryIssue(OWNER_A, spec(1)));
		authority.reload();
		assertEquals(4L, budget.snapshot().usedEntries());
		assertEquals(SeededLeafJobAuthority.ClaimStatus.CANCELLED,
			authority.claimResponse(OWNER_A, SeededLeafJobClaim.fromAuthorization(reloaded)));
		authority.stop();
		assertEquals(4L, budget.snapshot().usedEntries());
	}

	@Test
	void globalBudgetIsSharedAcrossOwnersAndFailuresDoNotCreatePendingJobs() {
		MutableNanoClock clock = new MutableNanoClock();
		DeterministicBudget exhausted = new DeterministicBudget(2);
		SeededLeafJobAuthority authority = authorityWithBudget(4, 2, 10, clock, exhausted);
		authority.start();
		accepted(authority.tryIssue(OWNER_A, spec(2)));
		assertEquals(SeededLeafJobAuthority.IssueStatus.GLOBAL_DISCLOSURE_LIMIT_REACHED,
			authority.tryIssue(OWNER_B, spec(1)).status());
		assertEquals(1, authority.pendingCount());
		assertEquals(2L, exhausted.snapshot().usedEntries());

		DeterministicBudget unavailable = new DeterministicBudget(10);
		unavailable.unavailable = true;
		SeededLeafJobAuthority unavailableAuthority = authorityWithBudget(4, 2, 10, clock, unavailable);
		unavailableAuthority.start();
		assertEquals(SeededLeafJobAuthority.IssueStatus.GLOBAL_DISCLOSURE_BUDGET_UNAVAILABLE,
			unavailableAuthority.tryIssue(OWNER_A, spec(1)).status());
		assertEquals(0, unavailableAuthority.pendingCount());
		assertEquals(0L, unavailable.snapshot().usedEntries());
	}

	@Test
	void newServerLifetimeResetsOwnerCounterButNotInjectedGlobalBudget() {
		MutableNanoClock clock = new MutableNanoClock();
		DeterministicBudget budget = new DeterministicBudget(4);
		SeededLeafJobAuthority authority = authorityWithBudget(2, 1, 1, clock, budget);
		authority.start();
		accepted(authority.tryIssue(OWNER_A, spec(1)));
		assertEquals(1, authority.disclosedEntriesFor(OWNER_A));
		authority.stop();
		authority.start();
		assertEquals(0, authority.disclosedEntriesFor(OWNER_A));
		accepted(authority.tryIssue(OWNER_A, spec(1)));
		assertEquals(2L, budget.snapshot().usedEntries());
	}

	private static SeededLeafJobAuthority authority(
		int maxTotal,
		int maxPerOwner,
		int maxDisclosure,
		MutableNanoClock clock,
		Supplier<UUID> jobIds
	) {
		return new SeededLeafJobAuthority(
			maxTotal,
			maxPerOwner,
			10,
			maxDisclosure,
			Duration.ofNanos(100),
			Duration.ofNanos(50),
			clock,
			jobIds,
			new SequentialContexts(),
			new SequentialAuthenticators()
		);
	}

	private static SeededLeafJobAuthority authorityWithBudget(
		int maxTotal,
		int maxPerOwner,
		int maxDisclosure,
		MutableNanoClock clock,
		SeededLeafGlobalDisclosureBudget budget
	) {
		return new SeededLeafJobAuthority(
			maxTotal,
			maxPerOwner,
			10,
			maxDisclosure,
			Duration.ofNanos(100),
			Duration.ofNanos(50),
			clock,
			new SequentialJobIds(),
			new SequentialContexts(),
			new SequentialAuthenticators(),
			budget
		);
	}

	private static SeededLeafJobSpec spec(int entries) {
		List<SeededLeafTranscript.Entry> transcript = new ArrayList<>();
		for (int index = 0; index < entries; index++) {
			transcript.add(new SeededLeafTranscript.Entry(
				SeededLeafTranscript.Kind.NORMAL_NOISE,
				"normal:minecraft:test",
				index,
				2L,
				3L,
				4L
			));
		}
		return new SeededLeafJobSpec(
			Identifier.parse("minecraft:overworld"),
			10,
			-20,
			Identifier.parse("minecraft:overworld"),
			-64,
			384,
			4,
			8,
			new SeededLeafTranscript(transcript)
		);
	}

	private static AuthorizedSeededLeafJob accepted(SeededLeafJobAuthority.IssueResult result) {
		assertEquals(SeededLeafJobAuthority.IssueStatus.ACCEPTED, result.status());
		return result.authorization().orElseThrow();
	}

	private static byte[] filled(byte value) {
		byte[] bytes = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		Arrays.fill(bytes, value);
		return bytes;
	}

	private static final class MutableNanoClock implements LongSupplier {
		private long nanos;

		@Override
		public long getAsLong() {
			return nanos;
		}

		private void advance(Duration duration) {
			nanos += duration.toNanos();
		}
	}

	private static final class SequentialJobIds implements Supplier<UUID> {
		private long next = 1L;

		@Override
		public UUID get() {
			return new UUID(0L, next++);
		}
	}

	private static final class SequentialContexts implements Supplier<OpaqueWorldgenContextId> {
		private int next = 0xaa;

		@Override
		public OpaqueWorldgenContextId get() {
			return OpaqueWorldgenContextId.fromHex(Integer.toHexString(next++).repeat(OpaqueWorldgenContextId.BYTE_LENGTH));
		}
	}

	private static final class SequentialAuthenticators implements Supplier<SeededLeafJobAuthenticator> {
		private int next = 0x11;

		@Override
		public SeededLeafJobAuthenticator get() {
			return SeededLeafJobAuthenticator.fromKey(filled((byte)next++));
		}
	}

	private static final class DeterministicBudget implements SeededLeafGlobalDisclosureBudget {
		private final long maximum;
		private long used;
		private long sequence;
		private boolean unavailable;

		private DeterministicBudget(long maximum) {
			this.maximum = maximum;
		}

		@Override
		public synchronized ChargeStatus tryCharge(int transcriptEntries) {
			if (unavailable) {
				return ChargeStatus.UNAVAILABLE;
			}
			if (transcriptEntries > maximum - used) {
				return ChargeStatus.EXHAUSTED;
			}
			used += transcriptEntries;
			sequence++;
			return ChargeStatus.ACCEPTED;
		}

		@Override
		public synchronized Snapshot snapshot() {
			return new Snapshot(unavailable ? State.FAILED : State.ACTIVE, maximum, used, sequence,
				unavailable ? "test_unavailable" : "");
		}
	}
}
