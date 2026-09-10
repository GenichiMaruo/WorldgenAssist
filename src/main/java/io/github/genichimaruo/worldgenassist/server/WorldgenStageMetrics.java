package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.world.level.ChunkPos;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import org.jspecify.annotations.Nullable;

public final class WorldgenStageMetrics {
	public static final WorldgenStageMetrics NOISE = new WorldgenStageMetrics("noise");

	private final String stage;
	private final AtomicLong generatedChunks = new AtomicLong();
	private final AtomicLong failures = new AtomicLong();
	private final AtomicLong totalElapsedNanos = new AtomicLong();
	private final AtomicLong activeOperations = new AtomicLong();
	private final AtomicLong peakActiveOperations = new AtomicLong();

	public WorldgenStageMetrics(String stage) {
		this.stage = Objects.requireNonNull(stage, "stage");
	}

	public <T> CompletableFuture<T> measure(ChunkPos chunkPos, Supplier<CompletableFuture<T>> operation) {
		return measure(chunkPos, operation, null);
	}

	public <T> CompletableFuture<T> measureAndThen(
		ChunkPos chunkPos,
		Supplier<CompletableFuture<T>> operation,
		Consumer<? super T> successObserver
	) {
		return measure(chunkPos, operation, Objects.requireNonNull(successObserver, "successObserver"));
	}

	private <T> CompletableFuture<T> measure(
		ChunkPos chunkPos,
		Supplier<CompletableFuture<T>> operation,
		@Nullable Consumer<? super T> successObserver
	) {
		Objects.requireNonNull(chunkPos, "chunkPos");
		Objects.requireNonNull(operation, "operation");

		long active = activeOperations.incrementAndGet();
		peakActiveOperations.accumulateAndGet(active, Math::max);
		long startedNanos = System.nanoTime();
		String startedThread = Thread.currentThread().getName();
		CompletableFuture<T> future;

		try {
			future = Objects.requireNonNull(operation.get(), "operation returned null");
		} catch (RuntimeException | Error error) {
			record(chunkPos, startedNanos, startedThread, error);
			throw error;
		}

		CompletableFuture<T> observed = future.whenComplete((result, error) -> {
			record(chunkPos, startedNanos, startedThread, error);
			if (error == null && successObserver != null) {
				successObserver.accept(result);
			}
		});
		return successObserver == null ? future : observed;
	}

	public Snapshot snapshot() {
		return new Snapshot(
			generatedChunks.get(),
			failures.get(),
			totalElapsedNanos.get(),
			activeOperations.get(),
			peakActiveOperations.get()
		);
	}

	private void record(ChunkPos chunkPos, long startedNanos, String startedThread, Throwable error) {
		long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
		totalElapsedNanos.addAndGet(elapsedNanos);
		String completionThread = Thread.currentThread().getName();
		double elapsedMillis = elapsedNanos / 1_000_000.0;

		if (error == null) {
			long generated = generatedChunks.incrementAndGet();
			long active = activeOperations.decrementAndGet();
			WorldgenAssist.LOGGER.info(
				"[CAWG] stage.complete stage={} chunk={},{} elapsed_ms={} started_thread={} completion_thread={} generated_chunks={} failures={} active={}",
				stage,
				chunkPos.x(),
				chunkPos.z(),
				elapsedMillis,
				startedThread,
				completionThread,
				generated,
				failures.get(),
				active
			);
		} else {
			long failed = failures.incrementAndGet();
			long active = activeOperations.decrementAndGet();
			WorldgenAssist.LOGGER.warn(
				"[CAWG] stage.failed stage={} chunk={},{} elapsed_ms={} started_thread={} completion_thread={} generated_chunks={} failures={} active={} error={}",
				stage,
				chunkPos.x(),
				chunkPos.z(),
				elapsedMillis,
				startedThread,
				completionThread,
				generatedChunks.get(),
				failed,
				active,
				error.toString()
			);
		}
	}

	public record Snapshot(
		long generatedChunks,
		long failures,
		long totalElapsedNanos,
		long activeOperations,
		long peakActiveOperations
	) {
		public long attempts() {
			return generatedChunks + failures;
		}

		public double totalElapsedMillis() {
			return totalElapsedNanos / 1_000_000.0;
		}
	}
}
