package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.Test;

class LocalWorldgenTaskBackendTest {
	@Test
	void sizesTheWaitingWindowProportionallyToWorkers() {
		assertEquals(0, LocalWorldgenTaskBackend.queueCapacityForWorkers(19, 0));
		assertEquals(19, LocalWorldgenTaskBackend.queueCapacityForWorkers(19, 1));
		assertEquals(38, LocalWorldgenTaskBackend.queueCapacityForWorkers(19, 2));
		assertEquals(256, LocalWorldgenTaskBackend.queueCapacityForWorkers(64, 4));
	}

	@Test
	void rateLimitsFallbackWarningsButKeepsTheFirstOneVisible() {
		assertTrue(LocalWorldgenTaskBackend.shouldWarnOnFallback(1L));
		assertTrue(LocalWorldgenTaskBackend.shouldWarnOnFallback(64L));
		assertTrue(LocalWorldgenTaskBackend.shouldWarnOnFallback(128L));
		assertFalse(LocalWorldgenTaskBackend.shouldWarnOnFallback(2L));
		assertFalse(LocalWorldgenTaskBackend.shouldWarnOnFallback(127L));
	}

	@Test
	void executesOnProjectOwnedWorker() throws Exception {
		LocalWorldgenTaskBackend backend = new LocalWorldgenTaskBackend(1, 4, "CAWG-Test-");
		try {
			assertEquals(LocalWorldgenTaskBackend.SCHEDULER_ID, backend.schedulerId());
			CompletableFuture<String> threadName = new CompletableFuture<>();
			backend.executorFor("noise", new ChunkPos(2, -3), Runnable::run).execute(
				() -> threadName.complete(Thread.currentThread().getName())
			);

			assertTrue(threadName.get(5, TimeUnit.SECONDS).startsWith("CAWG-Test-"));
			assertEquals(1L, backend.snapshot().submissionAttempts());
			assertEquals(1L, backend.snapshot().acceptedTasks());
			assertEquals(1L, backend.snapshot().startedTasks());
			assertEquals(0L, backend.snapshot().vanillaFallbacks());
			assertEquals(1, backend.snapshot().parallelism());
			assertEquals(5, backend.snapshot().admissionCapacity());
		} finally {
			backend.close();
		}
	}

	@Test
	void fallsBackWhenLocalExecutorRejectsBeforeMutationStarts() {
		LocalWorldgenTaskBackend backend = new LocalWorldgenTaskBackend(1, 1, "CAWG-Test-");
		backend.close();
		AtomicBoolean fallbackRan = new AtomicBoolean();

		backend.executorFor("noise", new ChunkPos(4, 5), Runnable::run).execute(() -> fallbackRan.set(true));

		assertTrue(fallbackRan.get());
		assertEquals(1L, backend.snapshot().submissionAttempts());
		assertEquals(0L, backend.snapshot().acceptedTasks());
		assertEquals(1L, backend.snapshot().vanillaFallbacks());
	}

	@Test
	void fallsBackWithoutBlockingWhenTheBoundedAdmissionCapacityIsFull() throws Exception {
		LocalWorldgenTaskBackend backend = new LocalWorldgenTaskBackend(1, 1, "CAWG-Bounded-");
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CompletableFuture<Void> secondCompleted = new CompletableFuture<>();
		AtomicBoolean fallbackRan = new AtomicBoolean();
		try {
			backend.executorFor("noise", new ChunkPos(8, 9), Runnable::run).execute(() -> {
				firstStarted.countDown();
				try {
					releaseFirst.await();
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

			backend.executorFor("noise", new ChunkPos(9, 9), Runnable::run).execute(() -> secondCompleted.complete(null));
			backend.executorFor("noise", new ChunkPos(10, 9), Runnable::run).execute(() -> fallbackRan.set(true));

			assertTrue(fallbackRan.get());
			assertEquals(3L, backend.snapshot().submissionAttempts());
			assertEquals(2L, backend.snapshot().acceptedTasks());
			assertEquals(1L, backend.snapshot().vanillaFallbacks());
			assertEquals(2, backend.snapshot().admittedTasks());
			assertEquals(1, backend.snapshot().activeTasks());
			assertEquals(0, backend.snapshot().availableAdmissionPermits());
			LocalWorldgenTaskBackend.SaturationSample saturation = backend.sampleSaturationAndResetPeaks();
			assertEquals(1, saturation.peakActiveTasks());
			assertEquals(2, saturation.peakAdmittedTasks());
		} finally {
			releaseFirst.countDown();
			secondCompleted.get(5, TimeUnit.SECONDS);
			backend.close();
		}
	}

	@Test
	void resetsSaturationPeaksAfterTheActiveIntervalDrains() throws Exception {
		LocalWorldgenTaskBackend backend = new LocalWorldgenTaskBackend(1, 1, "CAWG-Peak-");
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try {
			backend.executorFor("noise", new ChunkPos(11, 9), Runnable::run).execute(() -> {
				started.countDown();
				try {
					release.await();
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));

			LocalWorldgenTaskBackend.SaturationSample active = backend.sampleSaturationAndResetPeaks();
			assertEquals(1, active.peakActiveTasks());
			assertEquals(1, active.peakAdmittedTasks());

			release.countDown();
			awaitCompletedTasks(backend, 1L);

			LocalWorldgenTaskBackend.SaturationSample drained = backend.sampleSaturationAndResetPeaks();
			assertEquals(1, drained.peakActiveTasks());
			assertEquals(1, drained.peakAdmittedTasks());
			LocalWorldgenTaskBackend.SaturationSample idle = backend.sampleSaturationAndResetPeaks();
			assertEquals(0, idle.peakActiveTasks());
			assertEquals(0, idle.peakAdmittedTasks());
		} finally {
			release.countDown();
			backend.close();
		}
	}

	@Test
	void recreatesWorkerPoolForANewServerLifecycle() throws Exception {
		LocalWorldgenTaskBackend backend = new LocalWorldgenTaskBackend(1, 1, "CAWG-Restart-");
		backend.close();
		backend.start();
		try {
			CompletableFuture<String> threadName = new CompletableFuture<>();
			backend.executorFor("noise", new ChunkPos(6, 7), Runnable::run).execute(
				() -> threadName.complete(Thread.currentThread().getName())
			);

			assertTrue(threadName.get(5, TimeUnit.SECONDS).startsWith("CAWG-Restart-"));
			assertEquals(1L, backend.snapshot().acceptedTasks());
			assertEquals(0L, backend.snapshot().vanillaFallbacks());
		} finally {
			backend.close();
		}
	}

	private static void awaitCompletedTasks(LocalWorldgenTaskBackend backend, long expected) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
		while (backend.snapshot().completedTasks() < expected && System.nanoTime() < deadline) {
			Thread.sleep(1L);
		}
		assertEquals(expected, backend.snapshot().completedTasks());
	}
}
