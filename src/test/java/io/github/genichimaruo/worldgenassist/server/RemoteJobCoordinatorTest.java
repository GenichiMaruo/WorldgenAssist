package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RemoteJobCoordinatorTest {
	private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID WRONG_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final WorldgenContextFingerprint FINGERPRINT = WorldgenContextFingerprint.fromHex("12".repeat(32));

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void seedDisclosureDenyModeRejectsWorkerAndDispatchesNoJob() {
		FakeSender sender = new FakeSender();
		RemoteWorldgenConfig config = new RemoteWorldgenConfig(
			true,
			RemoteWorldgenConfig.SeedDisclosureMode.DENY,
			1,
			Duration.ofSeconds(1),
			1,
			false,
			20,
			1,
			0
		);
		RemoteJobCoordinator coordinator = new RemoteJobCoordinator(config, sender);

		WorkerAcceptedPayload response = coordinator.handleHello(
			OWNER,
			new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 1, "test")
		);

		assertEquals(WorkerAcceptedPayload.Status.REMOTE_DISABLED, response.status());
		assertTrue(coordinator.trySubmit(
			Identifier.parse("minecraft:overworld"),
			0,
			0,
			FINGERPRINT,
			RemoteJobCoordinatorTest::job
		).isEmpty());
		assertTrue(sender.jobs.isEmpty());
	}

	@Test
	void completesOneExactResponseAndRejectsReplay() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
		TerrainDensityResult result = result(submission.job());

		assertEquals(OWNER, submission.ownerId());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, fixture.coordinator.handleResult(OWNER, result));
		assertSame(result, submission.result().join());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.DUPLICATE, fixture.coordinator.handleResult(OWNER, result));
		assertEquals(0, fixture.coordinator.pendingCount());
		assertEquals(1, fixture.sender.jobs.size());
	}

	@Test
	void synchronousWaitCancelsPendingAndRestoresAdmissionAfterNestedException() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
		assertThrows(IllegalStateException.class, () -> fixture.coordinator.duringSynchronousChunkWait(() -> {
			assertTrue(submission.result().isCompletedExceptionally());
			assertEquals(0, fixture.coordinator.pendingCount());
			assertFalse(fixture.coordinator.isQuarantined(OWNER));
			assertTrue(fixture.submit().isEmpty());
			fixture.coordinator.duringSynchronousChunkWait(() -> assertTrue(fixture.submit().isEmpty()));
			assertTrue(fixture.submit().isEmpty());
			throw new IllegalStateException("vanilla wait failed");
		}));
		assertEquals(1, fixture.sender.jobs.size());
		RemoteJobCoordinator.Submission resumed = fixture.submit().orElseThrow();
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			fixture.coordinator.handleResult(OWNER, result(resumed.job())));
		assertTrue(resumed.result().isDone());
	}

	@Test
	void asynchronousDecodeClaimRejectsDuplicatesUntilCompletion() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
		TerrainDensityResult result = result(submission.job());

		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			fixture.coordinator.beginResult(OWNER, result.identity())
		);
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.DUPLICATE,
			fixture.coordinator.beginResult(OWNER, result.identity())
		);
		assertFalse(submission.result().isDone());
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			fixture.coordinator.completeClaimedResult(OWNER, result)
		);
		assertSame(result, submission.result().join());
	}

	@Test
	void decodeFailureCompletesFallbackAndReleasesWorker() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
		IllegalArgumentException failure = new IllegalArgumentException("corrupt stream");

		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			fixture.coordinator.beginResult(OWNER, submission.job().identity())
		);
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			fixture.coordinator.failClaimedResult(OWNER, submission.job().identity(), failure)
		);
		CompletionException thrown = assertThrows(CompletionException.class, submission.result()::join);
		assertSame(failure, thrown.getCause());
		assertTrue(fixture.submit().isPresent());
	}

	@Test
	void timeoutStillWinsWhileAResultIsBeingDecoded() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
		TerrainDensityResult result = result(submission.job());

		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			fixture.coordinator.beginResult(OWNER, result.identity())
		);
		fixture.clock.set(10L);
		assertEquals(1, fixture.coordinator.expireTimedOut(false));
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.EXPIRED,
			fixture.coordinator.completeClaimedResult(OWNER, result)
		);
		assertThrows(CompletionException.class, submission.result()::join);
	}

	@Test
	void wrongOwnerDoesNotConsumeThePendingJob() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
		TerrainDensityResult result = result(submission.job());

		assertEquals(PendingTerrainJobRegistry.ResponseStatus.OWNER_MISMATCH, fixture.coordinator.handleResult(WRONG_OWNER, result));
		assertFalse(submission.result().isDone());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, fixture.coordinator.handleResult(OWNER, result));
	}

	@Test
	void timeoutCancelsTheClientAndCompletesFallbackSignal() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
		fixture.clock.set(10L);

		assertEquals(1, fixture.coordinator.expireTimedOut());
		assertEquals(1, fixture.sender.cancels.size());
		assertThrows(CompletionException.class, submission.result()::join);
		assertEquals(0, fixture.coordinator.pendingCount());
	}

	@Test
	void watchdogTimeoutCompletesFallbackWithoutUsingNetworkingOffThread() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
		fixture.clock.set(10L);

		assertEquals(1, fixture.coordinator.expireTimedOut(false));
		assertTrue(fixture.sender.cancels.isEmpty());
		assertThrows(CompletionException.class, submission.result()::join);
		assertEquals(0, fixture.coordinator.pendingCount());
	}

	@Test
	void disconnectAndShutdownCompleteOutstandingAttempts() {
		Fixture disconnected = fixture();
		disconnected.hello(OWNER);
		RemoteJobCoordinator.Submission first = disconnected.submit().orElseThrow();
		assertEquals(1, disconnected.coordinator.disconnect(OWNER));
		assertThrows(java.util.concurrent.CancellationException.class, first.result()::join);

		Fixture shutdown = fixture();
		shutdown.hello(OWNER);
		RemoteJobCoordinator.Submission second = shutdown.submit().orElseThrow();
		assertEquals(1, shutdown.coordinator.shutdown());
		assertThrows(java.util.concurrent.CancellationException.class, second.result()::join);
	}

	@Test
	void sendFailureAndAmbiguousWorkersFallBackWithoutPendingState() {
		Fixture failedSend = fixture();
		failedSend.hello(OWNER);
		failedSend.sender.throwOnJob = true;
		assertTrue(failedSend.submit().isEmpty());
		assertEquals(0, failedSend.coordinator.pendingCount());

		Fixture ambiguous = fixture();
		ambiguous.hello(OWNER);
		ambiguous.hello(WRONG_OWNER);
		assertTrue(ambiguous.submit().isEmpty());
		assertEquals(0, ambiguous.sender.jobs.size());
	}

	@Test
	void ownerConstrainedPredictionNeverUsesAnotherWorkersConnection() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);

		assertEquals(OWNER, fixture.coordinator.soleWorkerOwner().orElseThrow());
		assertTrue(fixture.coordinator.trySubmitForOwner(
			WRONG_OWNER,
			Identifier.parse("minecraft:overworld"),
			0,
			0,
			FINGERPRINT,
			RemoteJobCoordinatorTest::job
		).isEmpty());
		assertTrue(fixture.coordinator.trySubmitForOwner(
			OWNER,
			Identifier.parse("minecraft:overworld"),
			0,
			0,
			FINGERPRINT,
			RemoteJobCoordinatorTest::job
		).isPresent());
	}

	@Test
	void explicitWorkerFailureCompletesTheAttemptImmediately() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();

		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			fixture.coordinator.handleFailure(
				OWNER,
				new TerrainJobFailurePayload(submission.job().identity(), TerrainJobFailurePayload.Reason.CONTEXT_MISMATCH)
			)
		);
		CompletionException exception = assertThrows(CompletionException.class, submission.result()::join);
		assertEquals(TerrainJobFailurePayload.Reason.CONTEXT_MISMATCH, ((RemoteWorkerException)exception.getCause()).reason());
	}

	@Test
	void rejectedReHandshakeCancelsOutstandingAttempt() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();

		fixture.coordinator.handleHello(OWNER, new WorkerHelloPayload(new WorldgenProtocolVersion(3), 1, "test"));

		assertThrows(java.util.concurrent.CancellationException.class, submission.result()::join);
		assertEquals(0, fixture.coordinator.pendingCount());
		assertTrue(fixture.submit().isEmpty());
	}

	@Test
	void reloadCancelsClientJobButRetainsWorkerRegistration() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();

		assertEquals(1, fixture.coordinator.cancelAllForReload());
		assertEquals(1, fixture.sender.cancels.size());
		assertThrows(java.util.concurrent.CancellationException.class, submission.result()::join);
		assertTrue(fixture.submit().isPresent());
	}

	@Test
	void dimensionChangeCancelsOnlyThatOwnerAndRetainsBothWorkers() {
		AtomicLong clock = new AtomicLong();
		AtomicLong identifiers = new AtomicLong();
		FakeSender sender = new FakeSender();
		RemoteJobCoordinator coordinator = new RemoteJobCoordinator(
			true,
			new WorkerRegistry(1),
			new PendingTerrainJobRegistry(2, 1, Duration.ofNanos(10), Duration.ofNanos(100), clock::get,
				() -> new UUID(0L, identifiers.incrementAndGet())),
			sender
		);
		coordinator.handleHello(OWNER, new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 1, "test"));
		coordinator.handleHello(WRONG_OWNER, new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 1, "test"));
		RemoteJobCoordinator.Submission changing = coordinator.trySubmitForOwner(OWNER,
			Identifier.parse("minecraft:overworld"), 0, 0, FINGERPRINT, RemoteJobCoordinatorTest::job).orElseThrow();
		RemoteJobCoordinator.Submission unaffected = coordinator.trySubmitForOwner(WRONG_OWNER,
			Identifier.parse("minecraft:overworld"), 1, 0, FINGERPRINT, RemoteJobCoordinatorTest::job).orElseThrow();

		assertEquals(1, coordinator.cancelForDimensionChange(OWNER));
		assertThrows(java.util.concurrent.CancellationException.class, changing.result()::join);
		assertEquals(1, sender.cancels.size());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.CANCELLED,
			coordinator.handleResult(OWNER, result(changing.job())));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			coordinator.handleResult(WRONG_OWNER, result(unaffected.job())));
		assertSame(unaffected.job().identity(), unaffected.result().join().identity());

		RemoteJobCoordinator.Submission destination = coordinator.trySubmitForOwner(OWNER,
			Identifier.parse("minecraft:the_nether"), 2, 0, FINGERPRINT, RemoteJobCoordinatorTest::job).orElseThrow();
		assertEquals(Identifier.parse("minecraft:the_nether"), destination.job().identity().dimension());
	}

	@Test
	void quarantinedWorkerCannotReRegisterUntilItDisconnects() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);
		RemoteJobCoordinator.Submission pending = fixture.submit().orElseThrow();

		assertEquals(1, fixture.coordinator.quarantine(OWNER));
		assertThrows(java.util.concurrent.CancellationException.class, pending.result()::join);
		assertTrue(fixture.submit().isEmpty());
		fixture.hello(OWNER);
		assertTrue(fixture.submit().isEmpty());

		assertEquals(0, fixture.coordinator.disconnect(OWNER));
		fixture.hello(OWNER);
		assertTrue(fixture.submit().isPresent());
	}

	@Test
	void successfulResultResetsTimeoutBreakerBeforeThreeConsecutiveTimeoutsQuarantine() {
		Fixture fixture = fixture();
		fixture.hello(OWNER);

		RemoteJobCoordinator.Submission preResetTimeout = fixture.submit().orElseThrow();
		fixture.clock.set(10L);
		assertEquals(1, fixture.coordinator.expireTimedOut(false));
		assertThrows(CompletionException.class, preResetTimeout.result()::join);

		fixture.clock.set(111L);
		RemoteJobCoordinator.Submission success = fixture.submit().orElseThrow();
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			fixture.coordinator.handleResult(OWNER, result(success.job()))
		);

		long registrationTime = 212L;
		for (int timeout = 1; timeout <= RemoteJobCoordinator.MAX_CONSECUTIVE_TIMEOUTS; timeout++) {
			fixture.clock.set(registrationTime);
			RemoteJobCoordinator.Submission submission = fixture.submit().orElseThrow();
			fixture.clock.set(registrationTime + 10L);
			assertEquals(1, fixture.coordinator.expireTimedOut(false));
			assertThrows(CompletionException.class, submission.result()::join);
			assertEquals(timeout == RemoteJobCoordinator.MAX_CONSECUTIVE_TIMEOUTS,
				fixture.coordinator.isQuarantined(OWNER));
			registrationTime += 111L;
		}

		assertTrue(fixture.submit().isEmpty());
		fixture.hello(OWNER);
		assertTrue(fixture.submit().isEmpty());
		assertEquals(0, fixture.coordinator.disconnect(OWNER));
		assertFalse(fixture.coordinator.isQuarantined(OWNER));
		fixture.clock.set(registrationTime + 111L);
		fixture.hello(OWNER);
		assertTrue(fixture.submit().isPresent());
	}

	private static Fixture fixture() {
		AtomicLong clock = new AtomicLong();
		FakeSender sender = new FakeSender();
		PendingTerrainJobRegistry pending = new PendingTerrainJobRegistry(
			1,
			1,
			Duration.ofNanos(10),
			Duration.ofNanos(100),
			clock::get,
			() -> UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
		);
		RemoteJobCoordinator coordinator = new RemoteJobCoordinator(
			true,
			new WorkerRegistry(1),
			pending,
			sender
		);
		return new Fixture(clock, sender, coordinator);
	}

	private static TerrainDensityJob job(TerrainJobIdentity identity) {
		return new TerrainDensityJob(identity, 8675309L, true, Identifier.parse("minecraft:overworld"), -64, 8, 4, 8);
	}

	private static TerrainDensityResult result(TerrainDensityJob job) {
		return new TerrainDensityResult(job.identity(), new double[job.sampleCount()], 5L);
	}

	private record Fixture(AtomicLong clock, FakeSender sender, RemoteJobCoordinator coordinator) {
		private void hello(UUID owner) {
			coordinator.handleHello(owner, new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 1, "test"));
		}

		private java.util.Optional<RemoteJobCoordinator.Submission> submit() {
			return coordinator.trySubmit(Identifier.parse("minecraft:overworld"), 0, 0, FINGERPRINT, RemoteJobCoordinatorTest::job);
		}
	}

	private static final class FakeSender implements RemoteJobSender {
		private final List<TerrainJobRequestPayload> jobs = new ArrayList<>();
		private final List<TerrainJobCancelPayload> cancels = new ArrayList<>();
		private boolean throwOnJob;

		@Override
		public boolean canSend(UUID ownerId) {
			return true;
		}

		@Override
		public void sendJob(UUID ownerId, TerrainJobRequestPayload payload) {
			if (throwOnJob) {
				throw new IllegalStateException("forced send failure");
			}
			jobs.add(payload);
		}

		@Override
		public void sendCancel(UUID ownerId, TerrainJobCancelPayload payload) {
			cancels.add(payload);
		}
	}
}
