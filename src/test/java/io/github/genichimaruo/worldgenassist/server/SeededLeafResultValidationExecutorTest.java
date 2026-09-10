package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobSpec;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SeededLeafResultValidationExecutorTest {
	private static final UUID OWNER = UUID.fromString("16539a37-f40d-4b02-a378-8dc17c7b22db");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void inactiveOwnerIsRejectedBeforeClaimAndMalformedDataIsDecodedOffAdmissionPath() throws Exception {
		Fixture fixture = fixture();
		try (SeededLeafResultValidationExecutor executor = new SeededLeafResultValidationExecutor(
			fixture.authority, 1, 1, Duration.ofMillis(100)
		)) {
			SeededLeafResultValidationExecutor.Outcome inactive = executor.submit(
				OWNER, fixture.malformedEnvelope, fixture.context
			).get(1, TimeUnit.SECONDS);
			assertEquals(SeededLeafResultValidationExecutor.Status.OWNER_INACTIVE, inactive.status());
			assertEquals(1, fixture.authority.pendingCount());

			executor.connectOwner(OWNER);
			SeededLeafResultValidationExecutor.Outcome malformed = executor.submit(
				OWNER, fixture.malformedEnvelope, fixture.context
			).get(1, TimeUnit.SECONDS);
			assertEquals(SeededLeafResultValidationExecutor.Status.INVALID_DENSITY_DATA, malformed.status());
			assertEquals(0, fixture.authority.pendingCount());
			// Completion precedes the worker's finally/release; get() is not a real-exit fence.
			awaitPendingCount(executor, 0, "malformed worker real-exit cleanup");
		}
	}

	@Test
	void closeIsIdempotentAndRejectsLaterSubmission() throws Exception {
		Fixture fixture = fixture();
		SeededLeafResultValidationExecutor executor = new SeededLeafResultValidationExecutor(
			fixture.authority, 1, 1, Duration.ofMillis(100)
		);
		executor.close();
		executor.close();
		assertEquals(SeededLeafResultValidationExecutor.Status.CLOSED, executor.submit(
			OWNER, fixture.malformedEnvelope, fixture.context
		).get(1, TimeUnit.SECONDS).status());
	}

	@Test
	void disconnectClosesAdmissionBeforeCancellationAndCancellationIsIdempotent() throws Exception {
		Fixture fixture = fixture();
		try (SeededLeafResultValidationExecutor executor = new SeededLeafResultValidationExecutor(
			fixture.authority, 1, 1, Duration.ofMillis(100)
		)) {
			executor.connectOwner(OWNER);
			assertEquals(0, executor.disconnectOwner(OWNER));
			assertEquals(0, executor.disconnectOwner(OWNER));
			assertEquals(SeededLeafResultValidationExecutor.Status.OWNER_INACTIVE, executor.submit(
				OWNER, fixture.malformedEnvelope, fixture.context
			).get(1, TimeUnit.SECONDS).status());
			assertEquals(1, fixture.authority.pendingCount());
			assertEquals(0, executor.cancelOwner(OWNER));
			assertEquals(0, executor.cancelAll());
		}
	}

	@Test
	void ownerEpochTrackerExhaustionFailsClosedBeforeClaimAdmission() throws Exception {
		Fixture fixture = fixture();
		try (SeededLeafResultValidationExecutor executor = new SeededLeafResultValidationExecutor(
			fixture.authority, 1, 1, Duration.ofMillis(100)
		)) {
			for (int index = 0; index < SeededLeafJobAuthority.MAX_TRACKED_DISCLOSURE_OWNERS; index++) {
				executor.connectOwner(new UUID(0L, index + 1L));
			}
			UUID overflowOwner = new UUID(1L, 1L);
			executor.connectOwner(overflowOwner);

			SeededLeafResultValidationExecutor.Outcome outcome = executor.submit(
				overflowOwner, fixture.malformedEnvelope, fixture.context
			).get(1, TimeUnit.SECONDS);

			assertEquals(SeededLeafResultValidationExecutor.Status.OWNER_STATE_UNAVAILABLE, outcome.status());
			assertEquals(1, fixture.authority.pendingCount(), "owner-state rejection must not consume the claim");
			assertTrue(executor.pendingCount() == 0, "no validation task must be admitted after tracker exhaustion");
		}
	}

	@Test
	@Timeout(5)
	void exactCapacityRejectsSecondSubmissionBeforeItsClaimWhileRealWorkerIsBlocked() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch workerEntered = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		try (SeededLeafResultValidationExecutor executor = executor(
			fixture, Duration.ofSeconds(1), claimed -> { }, claimed -> {
				workerEntered.countDown();
				await(releaseWorker, "release worker");
			}
		)) {
			executor.connectOwner(OWNER);
			CompletableFuture<SeededLeafResultValidationExecutor.Outcome> first = executor.submit(
				OWNER, fixture.malformedEnvelope, fixture.context
			);
			await(workerEntered, "worker entry");
			SeededLeafDensityResultEnvelope secondEnvelope = fixture.issueMalformed(OWNER);

			SeededLeafResultValidationExecutor.Outcome second = executor.submit(
				OWNER, secondEnvelope, fixture.context
			).get(1, TimeUnit.SECONDS);
			assertEquals(SeededLeafResultValidationExecutor.Status.QUEUE_FULL, second.status());
			assertEquals(SeededLeafJobAuthority.ClaimStatus.ACCEPTED,
				fixture.authority.claim(OWNER, secondEnvelope.claim()).status());

			releaseWorker.countDown();
			assertEquals(SeededLeafResultValidationExecutor.Status.INVALID_DENSITY_DATA,
				first.get(1, TimeUnit.SECONDS).status());
		}
	}

	@Test
	@Timeout(5)
	void timeoutLeavesCapacityOccupiedUntilInterruptedWorkerActuallyExits() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch workerEntered = new CountDownLatch(1);
		CountDownLatch workerInterrupted = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		CountDownLatch workerLeftHook = new CountDownLatch(1);
		try (SeededLeafResultValidationExecutor executor = executor(
			fixture, Duration.ofMillis(50), claimed -> { }, claimed -> {
				workerEntered.countDown();
				try {
					while (true) {
						try {
							releaseWorker.await();
							return;
						} catch (InterruptedException interrupted) {
							workerInterrupted.countDown();
						}
					}
				} finally {
					workerLeftHook.countDown();
				}
			}
		)) {
			try {
				executor.connectOwner(OWNER);
				CompletableFuture<SeededLeafResultValidationExecutor.Outcome> timedOut = executor.submit(
				OWNER, fixture.malformedEnvelope, fixture.context
				);
				await(workerEntered, "worker entry");
				assertEquals(SeededLeafResultValidationExecutor.Status.TIMED_OUT,
				timedOut.get(1, TimeUnit.SECONDS).status());
				await(workerInterrupted, "timeout interrupt");

				SeededLeafDensityResultEnvelope secondEnvelope = fixture.issueMalformed(OWNER);
				assertEquals(SeededLeafResultValidationExecutor.Status.QUEUE_FULL, executor.submit(
				OWNER, secondEnvelope, fixture.context
				).get(1, TimeUnit.SECONDS).status());
				assertEquals(1, executor.pendingCount(), "timeout completion must not release the running task slot");

				releaseWorker.countDown();
				await(workerLeftHook, "worker hook exit");
				awaitPendingCount(executor, 0, "task resource release");
			} finally {
				releaseWorker.countDown();
			}
		}
	}

	@Test
	@Timeout(5)
	void reloadRacingAfterClaimBeforeRegistrationReturnsStaleContext() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch claimed = new CountDownLatch(1);
		CountDownLatch releaseAdmission = new CountDownLatch(1);
		try (SeededLeafResultValidationExecutor executor = executor(
			fixture, Duration.ofSeconds(1), result -> {
				claimed.countDown();
				await(releaseAdmission, "release admission");
			}, result -> { }
		); ExecutorService submitter = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "SeededLeafExecutorTestSubmitter");
			thread.setDaemon(true);
			return thread;
		})) {
			executor.connectOwner(OWNER);
			CompletableFuture<SeededLeafResultValidationExecutor.Outcome> submission = CompletableFuture.supplyAsync(
				() -> executor.submit(OWNER, fixture.malformedEnvelope, fixture.context).join(), submitter
			);
			await(claimed, "claim before registration");
			executor.invalidateAndReloadAuthority();
			releaseAdmission.countDown();

			assertEquals(SeededLeafResultValidationExecutor.Status.STALE_CONTEXT,
				submission.get(1, TimeUnit.SECONDS).status());
			assertEquals(0, executor.pendingCount());
		}
	}

	@Test
	@Timeout(5)
	void claimIsConsumedOnSubmitterAndDecodeValidationRunsOnDedicatedWorker() throws Exception {
		Fixture fixture = fixture();
		String submitterThread = Thread.currentThread().getName();
		java.util.concurrent.atomic.AtomicReference<String> admissionThread = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<String> workerThread = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicInteger pendingAtWorker = new java.util.concurrent.atomic.AtomicInteger(-1);
		try (SeededLeafResultValidationExecutor executor = executor(
			fixture, Duration.ofSeconds(1), claimed -> admissionThread.set(Thread.currentThread().getName()), claimed -> {
				workerThread.set(Thread.currentThread().getName());
				pendingAtWorker.set(fixture.authority.pendingCount());
			}
		)) {
			executor.connectOwner(OWNER);
			assertEquals(SeededLeafResultValidationExecutor.Status.INVALID_DENSITY_DATA, executor.submit(
				OWNER, fixture.malformedEnvelope, fixture.context
			).get(1, TimeUnit.SECONDS).status());
			assertEquals(submitterThread, admissionThread.get(), "claim admission must be synchronous");
			assertEquals("CAWG-SeededLeafValidate", workerThread.get());
			assertEquals(0, pendingAtWorker.get(), "worker must see the one-shot claim already consumed");
		}
	}

	@Test
	@Timeout(5)
	void reconnectAfterClaimRejectsDelayedOldConnectionAsStaleWithoutRevival() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch claimed = new CountDownLatch(1);
		CountDownLatch releaseAdmission = new CountDownLatch(1);
		try (SeededLeafResultValidationExecutor executor = executor(
			fixture, Duration.ofSeconds(1), result -> {
				claimed.countDown();
				await(releaseAdmission, "reconnect admission release");
			}, result -> { }
		); ExecutorService submitter = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "SeededLeafReconnectSubmitter");
			thread.setDaemon(true);
			return thread;
		})) {
			executor.connectOwner(OWNER);
			CompletableFuture<SeededLeafResultValidationExecutor.Outcome> old = CompletableFuture.supplyAsync(
				() -> executor.submit(OWNER, fixture.malformedEnvelope, fixture.context).join(), submitter
			);
			await(claimed, "old claim");
			executor.disconnectOwner(OWNER);
			executor.connectOwner(OWNER);
			releaseAdmission.countDown();
			assertEquals(SeededLeafResultValidationExecutor.Status.STALE_OWNER_CONNECTION, old.get(1, TimeUnit.SECONDS).status());
			assertEquals(0, executor.pendingCount());
		}
	}

	@Test
	@Timeout(5)
	void disconnectRacingAfterClaimBeforeRegistrationBlocksOldAndNewAdmissions() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch claimed = new CountDownLatch(1);
		CountDownLatch releaseAdmission = new CountDownLatch(1);
		try (SeededLeafResultValidationExecutor executor = executor(
			fixture, Duration.ofSeconds(1), result -> {
				claimed.countDown();
				await(releaseAdmission, "release admission");
			}, result -> { }
		); ExecutorService submitter = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "SeededLeafExecutorTestSubmitter");
			thread.setDaemon(true);
			return thread;
		})) {
			executor.connectOwner(OWNER);
			CompletableFuture<SeededLeafResultValidationExecutor.Outcome> oldSubmission = CompletableFuture.supplyAsync(
				() -> executor.submit(OWNER, fixture.malformedEnvelope, fixture.context).join(), submitter
			);
			await(claimed, "claim before disconnect");
			executor.disconnectOwner(OWNER);
			SeededLeafDensityResultEnvelope newEnvelope = fixture.issueMalformed(OWNER);
			assertEquals(SeededLeafResultValidationExecutor.Status.OWNER_INACTIVE, executor.submit(
				OWNER, newEnvelope, fixture.context
			).get(1, TimeUnit.SECONDS).status());
			releaseAdmission.countDown();

			assertEquals(SeededLeafResultValidationExecutor.Status.OWNER_INACTIVE,
				oldSubmission.get(1, TimeUnit.SECONDS).status());
			assertEquals(0, executor.pendingCount());
		}
	}

	@Test
	@Timeout(5)
	void repeatedOwnerCancellationCompletesOnceAndReleasesQueuedCapacityBeforeRunningWorkExits() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch workerEntered = new CountDownLatch(1);
		CountDownLatch workerExited = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		try (SeededLeafResultValidationExecutor executor = executor(
			fixture, 2, Duration.ofSeconds(1), claimed -> { }, claimed -> {
				workerEntered.countDown();
				try {
					while (true) {
						try {
							releaseWorker.await();
							return;
						} catch (InterruptedException ignored) {
							// Deliberately retain the real invocation until the test releases it.
						}
					}
				} finally {
					workerExited.countDown();
				}
			}
		)) {
			try {
				executor.connectOwner(OWNER);
				AtomicInteger firstCompletions = new AtomicInteger();
				CompletableFuture<SeededLeafResultValidationExecutor.Outcome> first = executor.submit(
					OWNER, fixture.malformedEnvelope, fixture.context
				);
				first.whenComplete((outcome, failure) -> firstCompletions.incrementAndGet());
				await(workerEntered, "first worker entry");

				SeededLeafDensityResultEnvelope queuedEnvelope = fixture.issueMalformed(OWNER);
				AtomicInteger queuedCompletions = new AtomicInteger();
				CompletableFuture<SeededLeafResultValidationExecutor.Outcome> queued = executor.submit(
					OWNER, queuedEnvelope, fixture.context
				);
				queued.whenComplete((outcome, failure) -> queuedCompletions.incrementAndGet());
				assertEquals(2, executor.pendingCount(), "second task must be queued behind the held worker");

				assertEquals(2, executor.cancelOwner(OWNER));
				assertEquals(0, executor.cancelOwner(OWNER), "repeated cancellation must be idempotent");
				assertEquals(SeededLeafResultValidationExecutor.Status.CANCELLED, first.get(1, TimeUnit.SECONDS).status());
				assertEquals(SeededLeafResultValidationExecutor.Status.CANCELLED, queued.get(1, TimeUnit.SECONDS).status());
				assertEquals(1, firstCompletions.get(), "running task completion must have one winner");
				assertEquals(1, queuedCompletions.get(), "queued task completion must have one winner");
				assertEquals(1, executor.pendingCount(), "only the real running invocation retains capacity");

				SeededLeafDensityResultEnvelope nextEnvelope = fixture.issueMalformed(OWNER);
				CompletableFuture<SeededLeafResultValidationExecutor.Outcome> next = executor.submit(
					OWNER, nextEnvelope, fixture.context
				);
				assertFalse(next.isDone(), "queued-task removal must free one total-capacity permit");
				assertEquals(1, executor.cancelOwner(OWNER));
				assertEquals(SeededLeafResultValidationExecutor.Status.CANCELLED, next.get(1, TimeUnit.SECONDS).status());

				releaseWorker.countDown();
				await(workerExited, "running worker exit");
				awaitPendingCount(executor, 0, "running task resource release");
			} finally {
				releaseWorker.countDown();
			}
		}
	}

	@Test
	@Timeout(5)
	void repeatedReloadCancelsOnceButRetainsRunningCapacityUntilTheWorkerExits() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch workerEntered = new CountDownLatch(1);
		CountDownLatch workerExited = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		try (SeededLeafResultValidationExecutor executor = executor(
			fixture, Duration.ofSeconds(1), claimed -> { }, claimed -> {
				workerEntered.countDown();
				try {
					while (true) {
						try {
							releaseWorker.await();
							return;
						} catch (InterruptedException ignored) {
							// The lifecycle outcome must not release capacity before this exits.
						}
					}
				} finally {
					workerExited.countDown();
				}
			}
		)) {
			try {
				executor.connectOwner(OWNER);
				AtomicInteger completions = new AtomicInteger();
				CompletableFuture<SeededLeafResultValidationExecutor.Outcome> completion = executor.submit(
					OWNER, fixture.malformedEnvelope, fixture.context
				);
				completion.whenComplete((outcome, failure) -> completions.incrementAndGet());
				await(workerEntered, "worker entry before reload");

				assertEquals(1, executor.invalidateAndReloadAuthority().cancelledValidations());
				assertEquals(0, executor.invalidateAndReloadAuthority().cancelledValidations(),
					"repeated reload must not complete the same validation twice");
				assertEquals(SeededLeafResultValidationExecutor.Status.CANCELLED,
					completion.get(1, TimeUnit.SECONDS).status());
				assertEquals(1, completions.get());
				assertEquals(1, executor.pendingCount(), "reload cancellation retains the real worker slot");

				releaseWorker.countDown();
				await(workerExited, "worker exit after reload");
				awaitPendingCount(executor, 0, "reload task resource release");
			} finally {
				releaseWorker.countDown();
			}
		}
	}

	@Test
	@Timeout(5)
	void repeatedCloseCompletesQueuedAndRunningTasksOnceWithoutNonDaemonWorkerRetention() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch workerEntered = new CountDownLatch(1);
		CountDownLatch workerExited = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		SeededLeafResultValidationExecutor executor = executor(fixture, 2, Duration.ofSeconds(1), claimed -> { }, claimed -> {
			workerEntered.countDown();
			try {
				while (true) {
					try {
						releaseWorker.await();
						return;
					} catch (InterruptedException ignored) {
						// Close must retain capacity until this invocation is actually released.
					}
				}
			} finally {
				workerExited.countDown();
			}
		});
		try {
			executor.connectOwner(OWNER);
			AtomicInteger firstCompletions = new AtomicInteger();
			CompletableFuture<SeededLeafResultValidationExecutor.Outcome> first = executor.submit(
				OWNER, fixture.malformedEnvelope, fixture.context
			);
			first.whenComplete((outcome, failure) -> firstCompletions.incrementAndGet());
			await(workerEntered, "worker entry before close");
			AtomicInteger queuedCompletions = new AtomicInteger();
			CompletableFuture<SeededLeafResultValidationExecutor.Outcome> queued = executor.submit(
				OWNER, fixture.issueMalformed(OWNER), fixture.context
			);
			queued.whenComplete((outcome, failure) -> queuedCompletions.incrementAndGet());

			executor.close();
			executor.close();
			assertEquals(SeededLeafResultValidationExecutor.Status.CANCELLED, first.get(1, TimeUnit.SECONDS).status());
			assertEquals(SeededLeafResultValidationExecutor.Status.CANCELLED, queued.get(1, TimeUnit.SECONDS).status());
			assertEquals(1, firstCompletions.get());
			assertEquals(1, queuedCompletions.get());
			assertEquals(1, executor.pendingCount(), "only the held invocation may remain after close");
			releaseWorker.countDown();
			await(workerExited, "worker exit after close");
			awaitPendingCount(executor, 0, "close task resource release");
			assertNoNonDaemonThreadWithName("CAWG-SeededLeafValidate");
			assertNoNonDaemonThreadWithName("CAWG-SeededLeafValidationTimeout");
		} finally {
			releaseWorker.countDown();
			executor.close();
		}
	}

	@Test
	void outcomeAndCompletionTypesDoNotDeclareSeedTranscriptOrFingerprintState() {
		assertNoSensitiveField(SeededLeafResultValidationExecutor.Outcome.class);
		assertNoSensitiveField(SeededLeafDensityResultEnvelope.class);
		assertNoSensitiveField(SeededLeafDensityResultGate.AcceptedResult.class);
	}

	private static Fixture fixture() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		byte[] key = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		Arrays.fill(key, (byte)0x11);
		AtomicInteger jobSequence = new AtomicInteger();
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
			4, 4, 10, 10, Duration.ofSeconds(1), Duration.ofSeconds(1), System::nanoTime,
			() -> new UUID(0L, jobSequence.incrementAndGet()),
			() -> OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			() -> SeededLeafJobAuthenticator.fromKey(key)
		);
		authority.start();
		SeededLeafJobSpec spec = new SeededLeafJobSpec(
			Identifier.parse("minecraft:overworld"), 10, -20, Identifier.parse("minecraft:overworld"), -64, 16, 4, 8,
			new SeededLeafTranscript(List.of(new SeededLeafTranscript.Entry(
				SeededLeafTranscript.Kind.NORMAL_NOISE, "normal:minecraft:test", 1L, 2L, 3L, 4L
			)))
		);
		AuthorizedSeededLeafJob authorization = authority.tryIssue(OWNER, spec).authorization().orElseThrow();
		SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(authorization);
		return new Fixture(
			authority,
			spec,
			new SeededLeafDensityResultEnvelope(claim, 4_096, SeededLeafDensityResultEnvelope.Encoding.DEFLATE,
				new byte[] {1}, 0L, 0L),
			new SeededLeafResultValidationExecutor.ValidationContext(
				RandomState.create(settings.value(), noises, 8675309L), settings.value(), NoiseSettings.create(-64, 16, 1, 2)
			)
		);
	}

	private record Fixture(
		SeededLeafJobAuthority authority,
		SeededLeafJobSpec spec,
		SeededLeafDensityResultEnvelope malformedEnvelope,
		SeededLeafResultValidationExecutor.ValidationContext context
	) {
		private SeededLeafDensityResultEnvelope issueMalformed(UUID owner) {
			AuthorizedSeededLeafJob authorization = authority.tryIssue(owner, spec).authorization().orElseThrow();
			return new SeededLeafDensityResultEnvelope(
				SeededLeafJobClaim.fromAuthorization(authorization), 4_096, SeededLeafDensityResultEnvelope.Encoding.DEFLATE,
				new byte[] {1}, 0L, 0L
			);
		}
	}

	private static SeededLeafResultValidationExecutor executor(
		Fixture fixture,
		Duration timeout,
		SeededLeafResultValidationExecutor.AdmissionHook admissionHook,
		SeededLeafResultValidationExecutor.WorkerHook workerHook
	) {
		return new SeededLeafResultValidationExecutor(
			fixture.authority, 1, 1, timeout, new java.security.SecureRandom(), admissionHook, workerHook
		);
	}

	private static SeededLeafResultValidationExecutor executor(
		Fixture fixture,
		int maximumTasks,
		Duration timeout,
		SeededLeafResultValidationExecutor.AdmissionHook admissionHook,
		SeededLeafResultValidationExecutor.WorkerHook workerHook
	) {
		return new SeededLeafResultValidationExecutor(
			fixture.authority, maximumTasks, 1, timeout, new java.security.SecureRandom(), admissionHook, workerHook
		);
	}

	private static void assertNoSensitiveField(Class<?> type) {
		for (Field field : type.getDeclaredFields()) {
			String name = field.getName().toLowerCase(java.util.Locale.ROOT);
			assertFalse(name.contains("seed") || name.contains("transcript") || name.contains("fingerprint"),
				() -> type.getName() + " retains forbidden field " + field.getName());
		}
	}

	private static void assertNoNonDaemonThreadWithName(String name) {
		assertTrue(Thread.getAllStackTraces().keySet().stream()
			.filter(thread -> thread.getName().equals(name))
			.allMatch(Thread::isDaemon), () -> "non-daemon worker retained: " + name);
	}

	private static void await(CountDownLatch latch, String description) {
		try {
			assertTrue(latch.await(1, TimeUnit.SECONDS), () -> "Timed out waiting for " + description);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for " + description, interrupted);
		}
	}

	private static void awaitPendingCount(SeededLeafResultValidationExecutor executor, int expected, String description) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
		while (executor.pendingCount() != expected && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertEquals(expected, executor.pendingCount(), description);
	}
}
