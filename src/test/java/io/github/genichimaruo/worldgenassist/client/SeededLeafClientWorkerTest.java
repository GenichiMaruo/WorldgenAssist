package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobSpec;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import io.github.genichimaruo.worldgenassist.server.SeededLeafJobAuthenticator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SeededLeafClientWorkerTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void closeIsIdempotentAndRejectsLaterWork() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		try (SeededLeafClientWorker worker = new SeededLeafClientWorker()) {
			worker.close();
			worker.close();
			assertThrows(CompletionException.class, () -> worker.submit(
				registries, Identifier.parse("minecraft:overworld"), authorization()
			).join());
			assertThrows(CompletionException.class, () -> worker.submit(
				registries, Identifier.parse("minecraft:overworld"), authorization()
			).join());
		}
	}

	@Test
	void duplicateJobIsClassifiedAsDuplicateRatherThanGenericCapacity() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		AuthorizedSeededLeafJob job = authorization();
		try (SeededLeafClientWorker worker = new SeededLeafClientWorker()) {
			worker.submit(registries, Identifier.parse("minecraft:overworld"), job);
			CompletionException error = assertThrows(CompletionException.class, () -> worker.submit(
				registries, Identifier.parse("minecraft:overworld"), job
			).join());
			assertTrue(error.getCause().getMessage().contains("Duplicate seeded-leaf client job"));
		}
	}

	@Test
	@Timeout(5)
	void exactOneAttemptCapacityCountsRunningWorkBeforeAnyRealComputation() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		CountDownLatch computationEntered = new CountDownLatch(1);
		CountDownLatch releaseComputation = new CountDownLatch(1);
		try (SeededLeafClientWorker worker = new SeededLeafClientWorker(authorization -> {
			computationEntered.countDown();
			await(releaseComputation, "computation release");
		})) {
			AuthorizedSeededLeafJob firstJob = authorization(UUID.fromString("12345678-1234-1234-1234-123456789abc"));
			CompletableFuture<?> first = worker.submit(registries, Identifier.parse("minecraft:overworld"), firstJob);
			await(computationEntered, "computation entry");

			CompletionException full = assertThrows(CompletionException.class, () -> worker.submit(
				registries,
				Identifier.parse("minecraft:overworld"),
				authorization(UUID.fromString("22345678-1234-1234-1234-123456789abc"))
			).join());
			assertTrue(full.getCause().getMessage().contains("at capacity"));
			assertTrue(worker.cancel(firstJob.job().jobId()));
			assertTrue(first.isCancelled());
			releaseComputation.countDown();
		}
	}

	@Test
	@Timeout(5)
	void queuedAttemptIsObservableAndCancelledBeforeItReachesRealComputation() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		CountDownLatch executorBlockerEntered = new CountDownLatch(1);
		CountDownLatch releaseExecutorBlocker = new CountDownLatch(1);
		CountDownLatch executorBlockerExited = new CountDownLatch(1);
		AtomicInteger computationCalls = new AtomicInteger();
		try (SeededLeafClientWorker worker = new SeededLeafClientWorker(authorization -> computationCalls.incrementAndGet())) {
			ThreadPoolExecutor executor = executorOf(worker);
			executor.execute(() -> {
				executorBlockerEntered.countDown();
				try {
					await(releaseExecutorBlocker, "executor blocker release");
				} finally {
					executorBlockerExited.countDown();
				}
			});
			await(executorBlockerEntered, "executor blocker entry");

			AuthorizedSeededLeafJob queuedJob = authorization(UUID.fromString("72345678-1234-1234-1234-123456789abc"));
			CompletableFuture<?> queued = worker.submit(registries, Identifier.parse("minecraft:overworld"), queuedJob);
			assertEquals(1, worker.pendingCount(), "queued attempt must retain its admission until cancellation");
			assertEquals(1, executor.getQueue().size(), "attempt must be queued behind the occupied client thread");
			assertEquals(0, computationCalls.get(), "queued attempt must not enter real computation");

			assertTrue(worker.cancel(queuedJob.job().jobId()));
			assertTrue(queued.isCancelled());
			assertEquals(0, worker.pendingCount(), "removing a queued attempt must release its admission immediately");
			assertEquals(0, executor.getQueue().size(), "cancelled attempt must be removed from the executor queue");
			assertEquals(0, computationCalls.get(), "cancelled queued attempt must never enter real computation");

			AuthorizedSeededLeafJob replacementJob = authorization(UUID.fromString("82345678-1234-1234-1234-123456789abc"));
			CompletableFuture<?> replacement = worker.submit(
				registries, Identifier.parse("minecraft:overworld"), replacementJob
			);
			assertFalse(replacement.isDone(), "released admission must allow one replacement to queue");
			assertEquals(1, worker.pendingCount());
			assertEquals(1, executor.getQueue().size());
			assertTrue(worker.cancel(replacementJob.job().jobId()));
			assertTrue(replacement.isCancelled());
			assertEquals(0, worker.pendingCount());

			releaseExecutorBlocker.countDown();
			await(executorBlockerExited, "executor blocker exit");
		} finally {
			releaseExecutorBlocker.countDown();
		}
	}

	@Test
	@Timeout(5)
	void cancellationKeepsPendingAndCapacityUntilInterruptedInvocationExits() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		CountDownLatch computationEntered = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch releaseComputation = new CountDownLatch(1);
		CountDownLatch computationLeftHook = new CountDownLatch(1);
		try (SeededLeafClientWorker worker = new SeededLeafClientWorker(authorization -> {
			computationEntered.countDown();
			try {
				while (true) {
					try {
						releaseComputation.await();
						return;
					} catch (InterruptedException signal) {
						interrupted.countDown();
					}
				}
			} finally {
				computationLeftHook.countDown();
			}
		})) {
			try {
				AuthorizedSeededLeafJob firstJob = authorization(UUID.fromString("32345678-1234-1234-1234-123456789abc"));
			CompletableFuture<?> first = worker.submit(registries, Identifier.parse("minecraft:overworld"), firstJob);
				await(computationEntered, "computation entry");
				assertTrue(worker.cancel(firstJob.job().jobId()));
				assertTrue(first.isCancelled());
				await(interrupted, "worker cancellation interrupt");
				assertEquals(1, worker.pendingCount(), "cancelled completion must not release the running attempt");

				CompletionException full = assertThrows(CompletionException.class, () -> worker.submit(
				registries,
				Identifier.parse("minecraft:overworld"),
				authorization(UUID.fromString("42345678-1234-1234-1234-123456789abc"))
				).join());
				assertTrue(full.getCause().getMessage().contains("at capacity"));

				releaseComputation.countDown();
				await(computationLeftHook, "computation hook exit");
				awaitPendingCount(worker, 0);
			} finally {
				releaseComputation.countDown();
			}
		}
	}

	@Test
	@Timeout(5)
	void closeCancelsRunningAttemptOnceAndReleasesItsPermitOnlyAfterRealExit() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch exited = new CountDownLatch(1);
		SeededLeafClientWorker worker = new SeededLeafClientWorker(authorization -> {
			entered.countDown();
			try {
				while (true) {
					try {
						release.await();
						return;
					} catch (InterruptedException signal) {
						interrupted.countDown();
					}
				}
			} finally {
				exited.countDown();
			}
		});
		try {
			AuthorizedSeededLeafJob job = authorization(UUID.fromString("52345678-1234-1234-1234-123456789abc"));
			CompletableFuture<?> completion = worker.submit(registries, Identifier.parse("minecraft:overworld"), job);
			await(entered, "close computation entry");
			worker.close();
			worker.close();
			assertTrue(completion.isCancelled());
			await(interrupted, "close interrupt");
			assertEquals(1, worker.pendingCount(), "close must retain the running attempt until it exits");
			release.countDown();
			await(exited, "close computation exit");
			awaitPendingCount(worker, 0);
		} finally {
			release.countDown();
			worker.close();
		}
	}

	@Test
	@Timeout(5)
	void repeatedCancellationCompletesOnceAndDoesNotRetainTheAttemptAfterRealExit() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch exited = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (SeededLeafClientWorker worker = new SeededLeafClientWorker(authorization -> {
			entered.countDown();
			try {
				while (true) {
					try {
						release.await();
						return;
					} catch (InterruptedException ignored) {
						// Keep the real worker invocation observable until the test releases it.
					}
				}
			} finally {
				exited.countDown();
			}
		})) {
			try {
				AuthorizedSeededLeafJob job = authorization(UUID.fromString("62345678-1234-1234-1234-123456789abc"));
				AtomicInteger completions = new AtomicInteger();
				CompletableFuture<?> completion = worker.submit(registries, Identifier.parse("minecraft:overworld"), job);
				completion.whenComplete((result, failure) -> completions.incrementAndGet());
				await(entered, "repeated cancellation computation entry");

				assertTrue(worker.cancel(job.job().jobId()));
				assertFalse(worker.cancel(job.job().jobId()), "repeated cancellation must have no second winner");
				assertTrue(completion.isCancelled());
				assertEquals(1, completions.get(), "cancelled completion must be observed exactly once");
				assertEquals(1, worker.pendingCount(), "capacity remains occupied until the invocation exits");

				release.countDown();
				await(exited, "repeated cancellation computation exit");
				awaitPendingCount(worker, 0);
				assertNoNonDaemonThreadWithName("CAWG-SeededLeafClient-1");
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void workerAndCompletionModelsDoNotDeclareSeedTranscriptOrFingerprintState() {
		assertNoSensitiveField(SeededLeafClientWorker.class);
		assertNoSensitiveField(SeededLeafDensityResult.class);
		assertNoSensitiveField(SeededLeafDensityResultEnvelope.class);
	}

	private static AuthorizedSeededLeafJob authorization() {
		return authorization(UUID.fromString("12345678-1234-1234-1234-123456789abc"));
	}

	private static AuthorizedSeededLeafJob authorization(UUID jobId) {
		SeededLeafJob job = new SeededLeafJob(
			jobId,
			OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			new SeededLeafJobSpec(
				Identifier.parse("minecraft:overworld"), 0, 0, Identifier.parse("minecraft:overworld"),
				-64, 16, 4, 8, new SeededLeafTranscript(List.of(new SeededLeafTranscript.Entry(
					SeededLeafTranscript.Kind.NORMAL_NOISE, "normal:minecraft:test", 1L, 2L, 3L, 4L
				)))
			)
		);
		byte[] key = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		key[0] = 1;
		return SeededLeafJobAuthenticator.fromKey(key).authorize(job);
	}

	private static void await(CountDownLatch latch, String description) {
		try {
			assertTrue(latch.await(1, TimeUnit.SECONDS), () -> "Timed out waiting for " + description);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for " + description, interrupted);
		}
	}

	private static void awaitPendingCount(SeededLeafClientWorker worker, int expected) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
		while (worker.pendingCount() != expected && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertEquals(expected, worker.pendingCount(), "worker attempt did not release its capacity");
	}

	private static ThreadPoolExecutor executorOf(SeededLeafClientWorker worker) {
		try {
			Field field = SeededLeafClientWorker.class.getDeclaredField("executor");
			field.setAccessible(true);
			return (ThreadPoolExecutor) field.get(worker);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Could not inspect the client worker executor", exception);
		}
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
}
