package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.Test;

class WorldgenStageMetricsTest {
	@Test
	void recordsSuccessfulCompletionWithoutReplacingTheFuture() {
		WorldgenStageMetrics metrics = new WorldgenStageMetrics("test");
		CompletableFuture<String> original = new CompletableFuture<>();

		CompletableFuture<String> measured = metrics.measure(new ChunkPos(3, -7), () -> original);
		assertEquals(1L, metrics.snapshot().activeOperations());
		assertEquals(1L, metrics.snapshot().peakActiveOperations());
		original.complete("done");

		assertSame(original, measured);
		assertEquals("done", measured.join());
		assertEquals(1L, metrics.snapshot().generatedChunks());
		assertEquals(0L, metrics.snapshot().failures());
		assertEquals(1L, metrics.snapshot().attempts());
		assertEquals(0L, metrics.snapshot().activeOperations());
		assertTrue(metrics.snapshot().totalElapsedNanos() >= 0L);
	}

	@Test
	void recordsExceptionalCompletion() {
		WorldgenStageMetrics metrics = new WorldgenStageMetrics("test");
		CompletableFuture<String> original = new CompletableFuture<>();

		metrics.measure(new ChunkPos(-1, 2), () -> original);
		assertEquals(1L, metrics.snapshot().activeOperations());
		original.completeExceptionally(new IllegalStateException("failed"));

		assertEquals(0L, metrics.snapshot().generatedChunks());
		assertEquals(1L, metrics.snapshot().failures());
		assertEquals(1L, metrics.snapshot().attempts());
		assertEquals(0L, metrics.snapshot().activeOperations());
	}

	@Test
	void recordsSynchronousFailureAndRethrowsIt() {
		WorldgenStageMetrics metrics = new WorldgenStageMetrics("test");
		IllegalArgumentException failure = new IllegalArgumentException("failed before future creation");

		IllegalArgumentException thrown = assertThrows(
			IllegalArgumentException.class,
			() -> metrics.measure(new ChunkPos(0, 0), () -> {
				throw failure;
			})
		);

		assertSame(failure, thrown);
		assertEquals(0L, metrics.snapshot().generatedChunks());
		assertEquals(1L, metrics.snapshot().failures());
		assertEquals(0L, metrics.snapshot().activeOperations());
	}

	@Test
	void awaitedSuccessObserverRunsAfterMetricsAndBeforeReturnedCompletion() {
		WorldgenStageMetrics metrics = new WorldgenStageMetrics("test");
		CompletableFuture<String> original = new CompletableFuture<>();
		AtomicBoolean observed = new AtomicBoolean();

		CompletableFuture<String> measured = metrics.measureAndThen(new ChunkPos(4, 5), () -> original, result -> {
			assertEquals("done", result);
			assertEquals(1L, metrics.snapshot().generatedChunks());
			observed.set(true);
		});
		original.complete("done");

		assertNotSame(original, measured);
		assertEquals("done", measured.join());
		assertTrue(observed.get());
	}
}
