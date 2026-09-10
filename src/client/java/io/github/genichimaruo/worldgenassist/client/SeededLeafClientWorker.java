package io.github.genichimaruo.worldgenassist.client;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;

/** Bounded client worker for the unregistered seed-free protocol draft. */
final class SeededLeafClientWorker implements AutoCloseable {
	private static final int MAX_TASKS = 1;
	private static final ComputationHook NO_COMPUTATION_HOOK = authorization -> {
	};

	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
		1,
		1,
		0L,
		TimeUnit.MILLISECONDS,
		new ArrayBlockingQueue<>(MAX_TASKS),
		runnable -> {
			Thread thread = new Thread(runnable, "CAWG-SeededLeafClient-1");
			thread.setDaemon(true);
			return thread;
		},
		new ThreadPoolExecutor.AbortPolicy()
	);
	private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();
	private final Semaphore capacity = new Semaphore(MAX_TASKS);
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Object admissionLock = new Object();
	private final ComputationHook computationHook;

	SeededLeafClientWorker() {
		this(NO_COMPUTATION_HOOK);
	}

	SeededLeafClientWorker(ComputationHook computationHook) {
		this.computationHook = Objects.requireNonNull(computationHook, "computationHook");
	}

	CompletableFuture<SeededLeafDensityResultEnvelope> submit(
		HolderLookup.Provider worldgenRegistries,
		Identifier currentDimension,
		AuthorizedSeededLeafJob authorization
	) {
		Objects.requireNonNull(worldgenRegistries, "worldgenRegistries");
		Objects.requireNonNull(currentDimension, "currentDimension");
		Objects.requireNonNull(authorization, "authorization");
		CompletableFuture<SeededLeafDensityResultEnvelope> completion = new CompletableFuture<>();
		UUID jobId = authorization.job().jobId();
		synchronized (admissionLock) {
			if (closed.get()) {
				completion.completeExceptionally(new RejectedExecutionException("Seeded-leaf client worker is closed"));
				return completion;
			}
			if (attempts.containsKey(jobId)) {
				completion.completeExceptionally(new RejectedExecutionException("Duplicate seeded-leaf client job"));
				return completion;
			}
			if (!capacity.tryAcquire()) {
				completion.completeExceptionally(new RejectedExecutionException("Seeded-leaf client worker is at capacity"));
				return completion;
			}

			Attempt attempt = new Attempt(jobId, worldgenRegistries, currentDimension, authorization, completion);
			Attempt existing = attempts.putIfAbsent(jobId, attempt);
			if (existing != null) {
				capacity.release();
				completion.completeExceptionally(new RejectedExecutionException("Duplicate seeded-leaf client job"));
				return completion;
			}
			try {
				executor.execute(attempt);
			} catch (RejectedExecutionException exception) {
				attempt.finish();
				completion.completeExceptionally(exception);
			}
		}
		return completion;
	}

	boolean cancel(UUID jobId) {
		Objects.requireNonNull(jobId, "jobId");
		Attempt attempt = attempts.get(jobId);
		if (attempt == null) {
			return false;
		}
		return attempt.cancel();
	}

	int pendingCount() {
		return attempts.size();
	}

	@Override
	public void close() {
		synchronized (admissionLock) {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			attempts.values().forEach(Attempt::cancel);
			for (Runnable abandoned : executor.shutdownNow()) {
				((Attempt)abandoned).finish();
			}
		}
	}

	private final class Attempt implements Runnable {
		private final UUID jobId;
		private final HolderLookup.Provider worldgenRegistries;
		private final Identifier currentDimension;
		private final AuthorizedSeededLeafJob authorization;
		private final CompletableFuture<SeededLeafDensityResultEnvelope> completion;
		private final AtomicBoolean cancelRequested = new AtomicBoolean();
		private final AtomicBoolean resourcesReleased = new AtomicBoolean();
		private volatile Thread runner;

		private Attempt(
			UUID jobId,
			HolderLookup.Provider worldgenRegistries,
			Identifier currentDimension,
			AuthorizedSeededLeafJob authorization,
			CompletableFuture<SeededLeafDensityResultEnvelope> completion
		) {
			this.jobId = jobId;
			this.worldgenRegistries = worldgenRegistries;
			this.currentDimension = currentDimension;
			this.authorization = authorization;
			this.completion = completion;
		}

		@Override
		public void run() {
			runner = Thread.currentThread();
			try {
				if (cancelRequested.get()) {
					return;
				}
				computationHook.beforeCompute(authorization);
				if (cancelRequested.get()) {
					return;
				}
				SeededLeafDensityResult result = SeededLeafClientDensityComputer.compute(
					worldgenRegistries,
					currentDimension,
					authorization
				);
				if (!cancelRequested.get()) {
					completion.complete(SeededLeafDensityResultEnvelope.encode(result));
				}
			} catch (Throwable error) {
				if (!cancelRequested.get()) {
					completion.completeExceptionally(error);
				}
			} finally {
				runner = null;
				finish();
			}
		}

		private boolean cancel() {
			if (!cancelRequested.compareAndSet(false, true)) {
				return false;
			}
			completion.cancel(false);
			Thread currentRunner = runner;
			if (currentRunner != null) {
				currentRunner.interrupt();
			} else if (executor.remove(this)) {
				finish();
			}
			return true;
		}

		private void finish() {
			if (!resourcesReleased.compareAndSet(false, true)) {
				return;
			}
			attempts.remove(jobId, this);
			capacity.release();
		}
	}

	/** Package-private deterministic test boundary on the real client worker. */
	@FunctionalInterface
	interface ComputationHook {
		void beforeCompute(AuthorizedSeededLeafJob authorization);
	}
}
