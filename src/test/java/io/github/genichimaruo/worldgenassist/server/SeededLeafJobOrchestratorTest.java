package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.genichimaruo.worldgenassist.client.SeededLeafClientTestBridge;
import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests the inactive, server-local dispatch boundary without registering transport. */
class SeededLeafJobOrchestratorTest {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
	private static final UUID OWNER = UUID.fromString("7e818da4-63ae-47cf-b617-38a7f2b027c3");
	private static final UUID OTHER_OWNER = UUID.fromString("8f0da297-0ef7-4b60-ba09-54dfb3bc4dd7");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@Timeout(20)
	void successfulRealRecorderDummyClientAndValidatorProduceAReadyOffer() throws Exception {
		Fixture fixture = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(
			fixture.authority, 32, Duration.ofSeconds(5)
		)) {
			SeededLeafJobOrchestrator.Connection connection = orchestrator.connect(OWNER);
			AtomicInteger sends = new AtomicInteger();
			SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(connection, fixture.request, respondingExchange(fixture, sends));
			SeededLeafJobOrchestrator.Result result = result(attempt);
			assertEquals(SeededLeafJobOrchestrator.Status.READY, result.status());
			assertTrue(result.offer().isPresent());
			assertEquals(1, sends.get());
			assertEquals(0, fixture.authority.pendingCount(), "the validator must consume the authority claim");
			awaitNotBusy(orchestrator);
		}
	}

	@Test
	@Timeout(10)
	void timeoutKeepsRecordingCapacityUntilTheRealRecordingHookExits() throws Exception {
		Fixture fixture = fixture(100_000);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger sends = new AtomicInteger();
		try (SeededLeafJobOrchestrator orchestrator = orchestrator(fixture, Duration.ofMillis(500), request -> {
			entered.countDown();
			awaitUninterruptibly(release, "recording release");
		})) {
			SeededLeafJobOrchestrator.Connection connection = orchestrator.connect(OWNER);
			SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(connection, fixture.request, respondingExchange(fixture, sends));
			await(entered, "recording entry");
			assertEquals(SeededLeafJobOrchestrator.Status.TIMED_OUT, result(attempt).status());
			assertTrue(orchestrator.isBusy(), "a timed-out interrupt-insensitive recorder still owns capacity");
			assertEquals(SeededLeafJobOrchestrator.Status.BUSY,
				result(orchestrator.submit(connection, fixture.request, respondingExchange(fixture, sends))).status());
			assertEquals(0, sends.get());
			assertEquals(0, fixture.authority.globalDisclosureSnapshot().usedEntries());
			release.countDown();
			awaitNotBusy(orchestrator);
		} finally {
			release.countDown();
		}
	}

	@Test
	@Timeout(10)
	void cancelReloadAndDisconnectBeforeDispatchSpendNoBudgetAndNeverSend() throws Exception {
		for (Lifecycle lifecycle : Lifecycle.values()) {
			Fixture fixture = fixture(100_000);
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger sends = new AtomicInteger();
			try (SeededLeafJobOrchestrator orchestrator = orchestrator(fixture, Duration.ofSeconds(3), request -> {
				entered.countDown();
				awaitUninterruptibly(release, "recording release");
			})) {
				SeededLeafJobOrchestrator.Connection connection = orchestrator.connect(OWNER);
				SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(connection, fixture.request, respondingExchange(fixture, sends));
				await(entered, lifecycle + " recording entry");
				switch (lifecycle) {
					case CANCEL -> assertTrue(orchestrator.cancel(attempt));
					case RELOAD -> orchestrator.reload();
					case DISCONNECT -> orchestrator.disconnect(connection);
				}
				assertEquals(lifecycle.status, result(attempt).status());
				assertEquals(0, sends.get(), lifecycle + " must precede dispatch");
				assertEquals(0, fixture.authority.globalDisclosureSnapshot().usedEntries());
				release.countDown();
				awaitNotBusy(orchestrator);
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	@Timeout(10)
	void oldConnectionDisconnectIsIgnoredAfterReconnect() throws Exception {
		Fixture fixture = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(fixture.authority, 32, Duration.ofSeconds(5))) {
			SeededLeafJobOrchestrator.Connection oldConnection = orchestrator.connect(OWNER);
			SeededLeafJobOrchestrator.Connection current = orchestrator.connect(OWNER);
			orchestrator.disconnect(oldConnection);
			assertEquals(SeededLeafJobOrchestrator.Status.READY,
				result(orchestrator.submit(current, fixture.request, respondingExchange(fixture, new AtomicInteger()))).status());
		}
	}

	@Test
	@Timeout(20)
	void rejectedAuthorizationWrongClaimSendFailureAndPendingResponseAllFallback() throws Exception {
		Fixture rejectedFixture = fixture(1);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(rejectedFixture.authority, 32, Duration.ofSeconds(5))) {
			AtomicInteger sends = new AtomicInteger();
			assertEquals(SeededLeafJobOrchestrator.Status.AUTHORIZATION_REJECTED, result(orchestrator.submit(
				orchestrator.connect(OWNER), rejectedFixture.request, respondingExchange(rejectedFixture, sends)
			)).status());
			assertEquals(0, sends.get());
		}

		Fixture wrongFixture = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(wrongFixture.authority, 32, Duration.ofSeconds(5))) {
			SeededLeafJobOrchestrator.ClientExchange wrongClaim = new SeededLeafJobOrchestrator.ClientExchange() {
				@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
					SeededLeafJobOrchestrator.Connection connection, AuthorizedSeededLeafJob job
				) {
					SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(job);
					return CompletableFuture.completedFuture(new SeededLeafDensityResultEnvelope(
						new SeededLeafJobClaim(new UUID(0L, 91L), claim.contextId(), claim.authenticationTag()), 4_096,
						SeededLeafDensityResultEnvelope.Encoding.DEFLATE, new byte[] {1}, 0L, 0L
					));
				}
				@Override public void cancel(SeededLeafJobOrchestrator.Connection connection, SeededLeafJobClaim claim) { }
			};
			SeededLeafJobOrchestrator.Connection connection = orchestrator.connect(OWNER);
			assertEquals(SeededLeafJobOrchestrator.Status.RESULT_REJECTED,
				result(orchestrator.submit(connection, wrongFixture.request, wrongClaim)).status());
			awaitNotBusy(orchestrator);
			assertEquals(SeededLeafJobOrchestrator.Status.WORKER_QUARANTINED,
				result(orchestrator.submit(connection, wrongFixture.request, wrongClaim)).status());
			orchestrator.reload();
			assertEquals(SeededLeafJobOrchestrator.Status.WORKER_QUARANTINED,
				result(orchestrator.submit(connection, wrongFixture.request, wrongClaim)).status());
			assertEquals(SeededLeafJobOrchestrator.Status.READY, result(orchestrator.submit(
				orchestrator.connect(OWNER), wrongFixture.request, respondingExchange(wrongFixture, new AtomicInteger()))).status());
		}

		Fixture failingFixture = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(failingFixture.authority, 32, Duration.ofSeconds(5))) {
			assertEquals(SeededLeafJobOrchestrator.Status.EXCHANGE_FAILED, result(orchestrator.submit(
				orchestrator.connect(OWNER), failingFixture.request, failingExchange()
			)).status());
		}

		Fixture timeoutFixture = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(timeoutFixture.authority, 32, Duration.ofMillis(50))) {
			assertEquals(SeededLeafJobOrchestrator.Status.TIMED_OUT, result(orchestrator.submit(
				orchestrator.connect(OWNER), timeoutFixture.request, pendingExchange()
			)).status());
		}
	}

	@Test
	@Timeout(20)
	void validatedOfferIsOneShotAndRejectsLifecycleAndForeignOrchestratorUse() throws Exception {
		Fixture fixture = fixture(100_000);
		Fixture other = fixture(100_000);
		try (SeededLeafJobOrchestrator first = new SeededLeafJobOrchestrator(fixture.authority, 32, Duration.ofSeconds(5));
			 SeededLeafJobOrchestrator second = new SeededLeafJobOrchestrator(other.authority, 32, Duration.ofSeconds(5))) {
			SeededLeafJobOrchestrator.Connection connection = first.connect(OWNER);
			SeededLeafJobOrchestrator.ValidatedOffer offer = result(first.submit(
				connection, fixture.request, respondingExchange(fixture, new AtomicInteger())
			)).offer().orElseThrow();
			RecordingTarget target = new RecordingTarget();
			assertFalse(second.installIfCurrent(offer, target), "another orchestrator cannot consume this offer");
			assertTrue(first.installIfCurrent(offer, target));
			assertFalse(first.installIfCurrent(offer, target), "an offer is single use");

			SeededLeafJobOrchestrator.ValidatedOffer reloadOffer = readyOffer(first, connection, fixture);
			first.reload();
			assertFalse(first.installIfCurrent(reloadOffer, target));
			SeededLeafJobOrchestrator.Connection reconnected = first.connect(OWNER);
			SeededLeafJobOrchestrator.ValidatedOffer disconnectOffer = readyOffer(first, reconnected, fixture);
			first.disconnect(reconnected);
			assertFalse(first.installIfCurrent(disconnectOffer, target));
		}
	}

	@Test
	@Timeout(15)
	void readyOfferExpiresAtTheExactDeadlineWithoutSleeping() throws Exception {
		Fixture fixture = fixture(100_000);
		AtomicLong clock = new AtomicLong(42L);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(
			fixture.authority, 32, Duration.ofSeconds(5), request -> { }, clock::get
		)) {
			SeededLeafJobOrchestrator.ValidatedOffer offer = readyOffer(orchestrator, orchestrator.connect(OWNER), fixture);
			clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
			assertFalse(orchestrator.installIfCurrent(offer, new RecordingTarget()));
			assertFalse(orchestrator.installIfCurrent(offer, new RecordingTarget()), "expired use is terminal");
		}
	}

	@Test
	@Timeout(15)
	void threeIssuedTimeoutsQuarantineWhileASuccessResetsTheStreak() throws Exception {
		Fixture fixture = fixture(100_000);
		AtomicLong clock = new AtomicLong(1L);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(
			fixture.authority, 32, Duration.ofSeconds(5), request -> { }, clock::get
		)) {
			SeededLeafJobOrchestrator.Connection owner = orchestrator.connect(OWNER);
			for (int index = 0; index < 5; index++) {
				awaitNotBusy(orchestrator);
				CompletableFuture<SeededLeafDensityResultEnvelope> response = new CompletableFuture<>();
				CountDownLatch sent = new CountDownLatch(1);
				AtomicReference<AuthorizedSeededLeafJob> issued = new AtomicReference<>();
				AtomicInteger cancels = new AtomicInteger();
				SeededLeafJobOrchestrator.ClientExchange exchange = new SeededLeafJobOrchestrator.ClientExchange() {
					@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
						SeededLeafJobOrchestrator.Connection connection, AuthorizedSeededLeafJob job
					) { issued.set(job); sent.countDown(); return response; }
					@Override public void cancel(SeededLeafJobOrchestrator.Connection connection, SeededLeafJobClaim claim) {
						cancels.incrementAndGet();
					}
				};
				SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(owner, fixture.request, exchange);
				await(sent, "issued job");
				clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
				response.complete(new SeededLeafDensityResultEnvelope(SeededLeafJobClaim.fromAuthorization(issued.get()),
					4_096, SeededLeafDensityResultEnvelope.Encoding.DEFLATE, new byte[] {1}, 0L, 0L));
				assertEquals(SeededLeafJobOrchestrator.Status.TIMED_OUT, result(attempt).status());
				assertEquals(1, cancels.get());
				if (index == 1) {
					assertTrue(readyOffer(orchestrator, owner, fixture) != null, "success must reset two timeout strikes");
				}
			}
			awaitNotBusy(orchestrator);
			assertEquals(SeededLeafJobOrchestrator.Status.WORKER_QUARANTINED,
				result(orchestrator.submit(owner, fixture.request, pendingExchange())).status());
		}
	}

	@Test
	@Timeout(15)
	void authenticMalformedDataQuarantinesAndDoesNotReturnAnOffer() throws Exception {
		Fixture fixture = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(fixture.authority, 32, Duration.ofSeconds(5))) {
			SeededLeafJobOrchestrator.Connection owner = orchestrator.connect(OWNER);
			SeededLeafJobOrchestrator.ClientExchange malformed = new SeededLeafJobOrchestrator.ClientExchange() {
				@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
					SeededLeafJobOrchestrator.Connection connection, AuthorizedSeededLeafJob job
				) {
					return CompletableFuture.completedFuture(new SeededLeafDensityResultEnvelope(
						SeededLeafJobClaim.fromAuthorization(job), 4_096,
						SeededLeafDensityResultEnvelope.Encoding.DEFLATE, new byte[] {1}, 0L, 0L));
				}
				@Override public void cancel(SeededLeafJobOrchestrator.Connection connection, SeededLeafJobClaim claim) { }
			};
			SeededLeafJobOrchestrator.Result result = result(orchestrator.submit(owner, fixture.request, malformed));
			assertEquals(SeededLeafJobOrchestrator.Status.RESULT_REJECTED, result.status());
			assertTrue(result.offer().isEmpty());
			assertEquals(0, fixture.authority.pendingCount());
			assertEquals(SeededLeafJobOrchestrator.Status.WORKER_QUARANTINED,
				result(orchestrator.submit(owner, fixture.request, malformed)).status());
		}
	}

	@Test
	@Timeout(10)
	void exhaustedGlobalBudgetAndOwnerTrackerRejectBeforeRecording() throws Exception {
		SeededLeafVolatileDisclosureBudget budget = new SeededLeafVolatileDisclosureBudget(1);
		assertEquals(SeededLeafGlobalDisclosureBudget.ChargeStatus.ACCEPTED, budget.tryCharge(1));
		Fixture fixture = fixture(100_000, budget);
		AtomicInteger recordings = new AtomicInteger();
		try (SeededLeafJobOrchestrator orchestrator = orchestrator(fixture, Duration.ofSeconds(5), request -> recordings.incrementAndGet())) {
			assertEquals(SeededLeafJobOrchestrator.Status.AUTHORIZATION_REJECTED,
				result(orchestrator.submit(orchestrator.connect(OWNER), fixture.request, pendingExchange())).status());
			assertEquals(0, recordings.get());
		}
		Fixture second = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = orchestrator(second, Duration.ofSeconds(5), request -> recordings.incrementAndGet())) {
			SeededLeafJobOrchestrator.Connection connection = null;
			for (int index = 1; index <= SeededLeafJobAuthority.MAX_TRACKED_DISCLOSURE_OWNERS + 1; index++) {
				connection = orchestrator.connect(new UUID(15L, index));
			}
			assertEquals(SeededLeafJobOrchestrator.Status.OWNER_INACTIVE,
				result(orchestrator.submit(connection, second.request, pendingExchange())).status());
			assertEquals(0, recordings.get());
			assertEquals(0, second.authority.globalDisclosureSnapshot().usedEntries());
		}
	}

	@Test
	@Timeout(10)
	void reloadRejectsReentrantCompletionAdmissionBeforeRotatingAuthority() throws Exception {
		Fixture fixture = fixture(100_000);
		CountDownLatch sent = new CountDownLatch(1);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(fixture.authority, 32, Duration.ofSeconds(5))) {
			SeededLeafJobOrchestrator.Connection owner = orchestrator.connect(OWNER);
			SeededLeafJobOrchestrator.ClientExchange exchange = new SeededLeafJobOrchestrator.ClientExchange() {
				@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
					SeededLeafJobOrchestrator.Connection connection, AuthorizedSeededLeafJob job
				) { sent.countDown(); return new CompletableFuture<>(); }
				@Override public void cancel(SeededLeafJobOrchestrator.Connection connection, SeededLeafJobClaim claim) { }
			};
			SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(owner, fixture.request, exchange);
			await(sent, "dispatch before reload");
			CompletableFuture<SeededLeafJobOrchestrator.Result> reentrant = attempt.completion()
				.thenCompose(ignored -> orchestrator.submit(owner, fixture.request, exchange).completion()).toCompletableFuture();
			orchestrator.reload();
			assertEquals(SeededLeafJobOrchestrator.Status.STALE_CONTEXT, result(attempt).status());
			assertEquals(SeededLeafJobOrchestrator.Status.STALE_CONTEXT, reentrant.get(2, TimeUnit.SECONDS).status());
			assertEquals(0, fixture.authority.pendingCount());
		}
	}

	@Test
	@Timeout(20)
	void queuedDispatchSendsOnceRejectsDuplicatesAndValidatesRealClientResponse() throws Exception {
		Fixture fixture = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(fixture.authority, 32, Duration.ofSeconds(5));
			SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator)) {
			SeededLeafJobOrchestrator.Connection owner = orchestrator.connect(OWNER);
			CountDownLatch queued = new CountDownLatch(1);
			SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(owner, fixture.request, queuedExchange(queue, queued));
			await(queued, "queued request");
			AtomicReference<AuthorizedSeededLeafJob> sent = new AtomicReference<>();
			AtomicInteger sends = new AtomicInteger();
			for (int i = 0; i < 2; i++) {
				queue.drain((connection, job) -> { assertTrue(connection == owner); sent.set(job); sends.incrementAndGet(); },
					(connection, claim) -> { throw new AssertionError("Unexpected cancel"); });
			}
			assertEquals(1, sends.get());
			assertEquals(1, queue.pendingCount());
			org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class,
				() -> queue.send(owner, sent.get()));
			SeededLeafDensityResultEnvelope envelope = SeededLeafDensityResultEnvelope.encode(
				SeededLeafClientTestBridge.compute(fixture.registries, OVERWORLD, sent.get()));
			assertTrue(queue.receive(owner, envelope));
			assertFalse(queue.receive(owner, envelope));
			assertFalse(queue.fail(owner, envelope.claim()));
			assertEquals(SeededLeafJobOrchestrator.Status.READY, result(attempt).status());
			assertEquals(0, queue.pendingCount());
		}
	}

	@Test
	@Timeout(30)
	void queuedLifecycleChangesDiscardUnsentJobsWithoutRefundingDisclosure() throws Exception {
		for (Lifecycle lifecycle : Lifecycle.values()) {
			Fixture fixture = fixture(100_000);
			try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(fixture.authority, 32, Duration.ofSeconds(5));
				SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator)) {
				SeededLeafJobOrchestrator.Connection owner = orchestrator.connect(OWNER);
				CountDownLatch queued = new CountDownLatch(1);
				SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(owner, fixture.request, queuedExchange(queue, queued));
				await(queued, "request queued before " + lifecycle);
				long spent = fixture.authority.globalDisclosureSnapshot().usedEntries();
				assertTrue(spent > 0);
				switch (lifecycle) {
					case CANCEL -> orchestrator.cancel(attempt);
					case RELOAD -> orchestrator.reload();
					case DISCONNECT -> orchestrator.disconnect(owner);
				}
				queue.drain((connection, job) -> { throw new AssertionError("Stale request sent"); },
					(connection, claim) -> { throw new AssertionError("Unsent job needs no network cancel"); });
				assertEquals(lifecycle.status, result(attempt).status());
				assertEquals(0, queue.pendingCount());
				assertEquals(spent, fixture.authority.globalDisclosureSnapshot().usedEntries());
			}
		}
	}

	@Test
	@Timeout(20)
	void sentCancellationIsBoundedIdempotentAndOldConnectionCannotAnswer() throws Exception {
		Fixture fixture = fixture(100_000);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(fixture.authority, 32, Duration.ofSeconds(5));
			SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator)) {
			SeededLeafJobOrchestrator.Connection old = orchestrator.connect(OWNER);
			SeededLeafJobOrchestrator.Connection owner = orchestrator.connect(OWNER);
			CountDownLatch queued = new CountDownLatch(1);
			SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(owner, fixture.request, queuedExchange(queue, queued));
			await(queued, "queue before cancel");
			AtomicReference<AuthorizedSeededLeafJob> sent = new AtomicReference<>();
			queue.drain((connection, job) -> sent.set(job), (connection, claim) -> { });
			SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(sent.get());
			SeededLeafDensityResultEnvelope envelope = new SeededLeafDensityResultEnvelope(claim, 4096,
				SeededLeafDensityResultEnvelope.Encoding.DEFLATE, new byte[] {1}, 0, 0);
			assertFalse(queue.receive(old, envelope));
			assertFalse(queue.fail(old, claim));
			queue.cancel(old, claim);
			assertEquals(1, queue.pendingCount());
			orchestrator.cancel(attempt);
			queue.cancel(owner, claim);
			AtomicInteger cancels = new AtomicInteger();
			for (int i = 0; i < 2; i++) {
				queue.drain((connection, job) -> { throw new AssertionError("Resent request"); },
					(connection, cancelled) -> { assertEquals(claim, cancelled); cancels.incrementAndGet(); });
			}
			assertEquals(1, cancels.get());
			assertFalse(queue.receive(owner, envelope));
			assertEquals(SeededLeafJobOrchestrator.Status.CANCELLED, result(attempt).status());
		}
	}

	@Test
	@Timeout(20)
	void queuedTimeoutAndTransportFailureCompleteThroughFallback() throws Exception {
		for (int mode = 0; mode < 3; mode++) {
			Fixture fixture = fixture(100_000);
			try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(fixture.authority, 32, Duration.ofSeconds(1));
				SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator)) {
				CountDownLatch queued = new CountDownLatch(1);
				SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(orchestrator.connect(OWNER), fixture.request, queuedExchange(queue, queued));
				await(queued, "queue before failure");
				if (mode == 1) { queue.drain((connection, job) -> { throw new IllegalStateException("send failed"); }, (connection, claim) -> { }); }
				if (mode == 2) { queue.close(); }
				assertFalse(result(attempt).offer().isPresent());
				queue.drain((connection, job) -> { throw new AssertionError("Expired request sent"); }, (connection, claim) -> { });
				assertEquals(0, queue.pendingCount());
			}
		}
	}

	@Test
	@Timeout(20)
	void synchronousWaitCancelsPendingAndNestedScopesBlockRecordingUntilFullyResumed() throws Exception {
		Fixture fixture = fixture(100_000);
		AtomicInteger recordings = new AtomicInteger();
		try (SeededLeafJobOrchestrator orchestrator = orchestrator(fixture, Duration.ofSeconds(5), request -> recordings.incrementAndGet());
			SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator)) {
			SeededLeafJobOrchestrator.Connection owner = orchestrator.connect(OWNER);
			CountDownLatch queued = new CountDownLatch(1);
			SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(owner, fixture.request, queuedExchange(queue, queued));
			await(queued, "request before synchronous wait");
			CompletableFuture<SeededLeafJobOrchestrator.Result> reentrant = attempt.completion()
				.thenCompose(ignored -> orchestrator.submit(owner, fixture.request, queue).completion()).toCompletableFuture();
			assertTrue(orchestrator.suspendAdmission());
			assertEquals(SeededLeafJobOrchestrator.Status.CANCELLED, result(attempt).status());
			assertEquals(SeededLeafJobOrchestrator.Status.BUSY, reentrant.get(2, TimeUnit.SECONDS).status());
			assertFalse(orchestrator.suspendAdmission());
			orchestrator.resumeAdmission();
			assertTrue(orchestrator.isBusy());
			assertEquals(SeededLeafJobOrchestrator.Status.BUSY, result(orchestrator.submit(owner, fixture.request, queue)).status());
			queue.drain((connection, job) -> { throw new AssertionError("Synchronous wait sent a job"); }, (connection, claim) -> { });
			assertEquals(1, recordings.get());
			orchestrator.resumeAdmission();
			awaitNotBusy(orchestrator);
			assertEquals(SeededLeafJobOrchestrator.Status.READY,
				result(orchestrator.submit(owner, fixture.request, respondingExchange(fixture, new AtomicInteger()))).status());
			org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, orchestrator::resumeAdmission);
		}
	}

	private static SeededLeafJobOrchestrator.ClientExchange queuedExchange(SeededLeafDispatchQueue queue, CountDownLatch queued) {
		return new SeededLeafJobOrchestrator.ClientExchange() {
			@Override public java.util.concurrent.CompletionStage<SeededLeafDensityResultEnvelope> send(
				SeededLeafJobOrchestrator.Connection owner, AuthorizedSeededLeafJob job
			) {
				var response = queue.send(owner, job);
				queued.countDown();
				return response;
			}
			@Override public void cancel(SeededLeafJobOrchestrator.Connection owner, SeededLeafJobClaim claim) { queue.cancel(owner, claim); }
		};
	}

	private static SeededLeafJobOrchestrator.ValidatedOffer readyOffer(
		SeededLeafJobOrchestrator orchestrator, SeededLeafJobOrchestrator.Connection connection, Fixture fixture
	) throws Exception {
		awaitNotBusy(orchestrator);
		return result(orchestrator.submit(connection, fixture.request, respondingExchange(fixture, new AtomicInteger())))
			.offer().orElseThrow();
	}

	private static SeededLeafJobOrchestrator orchestrator(
		Fixture fixture, Duration timeout, SeededLeafJobOrchestrator.RecordingHook hook
	) {
		return new SeededLeafJobOrchestrator(fixture.authority, 32, timeout, hook);
	}

	private static SeededLeafJobOrchestrator.ClientExchange respondingExchange(Fixture fixture, AtomicInteger sends) {
		return new SeededLeafJobOrchestrator.ClientExchange() {
			@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
				SeededLeafJobOrchestrator.Connection connection, AuthorizedSeededLeafJob job
			) {
				sends.incrementAndGet();
				return CompletableFuture.supplyAsync(() -> {
					SeededLeafDensityResult result = SeededLeafClientTestBridge.compute(fixture.registries, OVERWORLD, job);
					return SeededLeafDensityResultEnvelope.encode(result);
				});
			}
			@Override public void cancel(SeededLeafJobOrchestrator.Connection connection, SeededLeafJobClaim claim) { }
		};
	}

	private static SeededLeafJobOrchestrator.ClientExchange failingExchange() {
		return new SeededLeafJobOrchestrator.ClientExchange() {
			@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
				SeededLeafJobOrchestrator.Connection connection, AuthorizedSeededLeafJob job
			) { return CompletableFuture.failedFuture(new IllegalStateException("synthetic send failure")); }
			@Override public void cancel(SeededLeafJobOrchestrator.Connection connection, SeededLeafJobClaim claim) { }
		};
	}

	private static SeededLeafJobOrchestrator.ClientExchange pendingExchange() {
		return new SeededLeafJobOrchestrator.ClientExchange() {
			@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
				SeededLeafJobOrchestrator.Connection connection, AuthorizedSeededLeafJob job
			) { return new CompletableFuture<>(); }
			@Override public void cancel(SeededLeafJobOrchestrator.Connection connection, SeededLeafJobClaim claim) { }
		};
	}

	private static SeededLeafJobOrchestrator.Result result(SeededLeafJobOrchestrator.Attempt attempt) throws Exception {
		return attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
	}

	private static Fixture fixture(int maximumDisclosedEntries) {
		return fixture(maximumDisclosedEntries, new SeededLeafVolatileDisclosureBudget(100_000));
	}

	private static Fixture fixture(int maximumDisclosedEntries, SeededLeafGlobalDisclosureBudget budget) {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		NoiseSettings slice = NoiseSettings.create(-64, 16, 1, 2);
		RandomState randomState = RandomState.create(settings.value(), noises, 8675309L);
		AtomicInteger sequence = new AtomicInteger();
		byte[] key = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		Arrays.fill(key, (byte)0x4c);
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
			8, 8, 100_000, maximumDisclosedEntries, Duration.ofSeconds(10), Duration.ofSeconds(10), System::nanoTime,
			() -> new UUID(0L, sequence.incrementAndGet()),
			() -> OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			() -> SeededLeafJobAuthenticator.fromKey(key), budget
		);
		authority.start();
		return new Fixture(registries, authority, new SeededLeafJobOrchestrator.RecordingRequest(
			OVERWORLD, 10, -20, randomState, settings, slice, 100_000
		));
	}

	private static void await(CountDownLatch latch, String description) {
		try {
			assertTrue(latch.await(2, TimeUnit.SECONDS), () -> "Timed out waiting for " + description);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for " + description, interrupted);
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch, String description) {
		boolean interrupted = false;
		try {
			while (true) {
				try {
					await(latch, description);
					return;
				} catch (AssertionError failure) {
					if (Thread.interrupted()) {
						interrupted = true;
						continue;
					}
					throw failure;
				}
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static void awaitNotBusy(SeededLeafJobOrchestrator orchestrator) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (orchestrator.isBusy() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertFalse(orchestrator.isBusy());
	}

	private enum Lifecycle {
		CANCEL(SeededLeafJobOrchestrator.Status.CANCELLED),
		RELOAD(SeededLeafJobOrchestrator.Status.STALE_CONTEXT),
		DISCONNECT(SeededLeafJobOrchestrator.Status.DISCONNECTED);
		private final SeededLeafJobOrchestrator.Status status;
		Lifecycle(SeededLeafJobOrchestrator.Status status) { this.status = status; }
	}

	private record Fixture(
		HolderLookup.Provider registries, SeededLeafJobAuthority authority, SeededLeafJobOrchestrator.RecordingRequest request
	) { }

	private static final class RecordingTarget implements RemoteDensityTarget {
		@Override public void worldgenAssist$installRemoteDensity(RemoteDensityField field) { }
		@Override public void worldgenAssist$clearRemoteDensity(UUID jobId) { }
	}
}
