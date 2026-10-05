package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Bounded original decoration bodies. Conflicting jobs never overtake each other. */
public final class FeatureStageQueue implements AutoCloseable {
	private final int capacity;
	private final int workers;
	private final ExecutorService executor;
	private final List<Job<?>> pending = new ArrayList<>();
	private final List<Job<?>> active = new ArrayList<>();
	private boolean closing;
	private Runnable capacityListener = () -> {};
	private long submitted, completed, failed, bypassed;
	private int peakActive, peakAdmitted;
	private int executing, peakExecuting;

	public FeatureStageQueue(int workers, int capacity, String threadName) {
		if (workers < 1 || workers > 2 || capacity < workers) throw new IllegalArgumentException("feature queue bounds");
		this.workers = workers;
		this.capacity = capacity;
		var sequence = new java.util.concurrent.atomic.AtomicInteger();
		this.executor = Executors.newFixedThreadPool(workers, task -> {
			Thread thread = new Thread(task, threadName + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
	}

	public synchronized void onCapacityChanged(Runnable listener) {
		capacityListener = Objects.requireNonNull(listener);
	}

	public synchronized boolean hasCapacity(int reservation) {
		return !closing && reservation > 0 && pending.size() + active.size() <= capacity - reservation;
	}

	public <T> CompletableFuture<T> submit(Footprint footprint, Supplier<T> body) {
		Objects.requireNonNull(body);
		return submitAsync(footprint, () -> CompletableFuture.completedFuture(body.get()));
	}

	public <T> CompletableFuture<T> submitAsync(Footprint footprint, Supplier<CompletableFuture<T>> body) {
		Job<T> job = new Job<>(Objects.requireNonNull(footprint), Objects.requireNonNull(body));
		synchronized (this) {
			if (!hasCapacity(1)) throw new RejectedExecutionException("feature admission capacity or shutdown");
			pending.add(job);
			submitted++;
			peakAdmitted = Math.max(peakAdmitted, pending.size() + active.size());
			dispatch();
		}
		// Cancellation of the observer cannot release a footprint while its body still writes.
		return job.result.copy();
	}

	private void dispatch() {
		while (executing < workers) {
			int eligible = -1;
			for (int i = 0; i < pending.size(); i++) {
				Job<?> candidate = pending.get(i);
				boolean conflict = active.stream().anyMatch(other -> candidate.footprint.intersects(other.footprint));
				for (int j = 0; !conflict && j < i; j++) conflict = candidate.footprint.intersects(pending.get(j).footprint);
				if (!conflict) { eligible = i; break; }
			}
			if (eligible < 0) return;
			if (eligible != 0) bypassed++;
			Job<?> job = pending.remove(eligible);
			active.add(job);
			peakActive = Math.max(peakActive, active.size());
			executing++; peakExecuting = Math.max(peakExecuting, executing);
			// Only at most two original body invocations are submitted. Pending asynchronous
			// futures retain their footprint/capacity, but do not consume a CPU execution permit.
			executor.execute(() -> run(job));
		}
		if (closing && pending.isEmpty() && active.isEmpty()) executor.shutdown();
	}

	private <T> void run(Job<T> job) {
		try {
			CompletableFuture<T> future;
			try { future = Objects.requireNonNull(job.body.get(), "feature body returned null"); }
			catch (Throwable failure) { finish(job, null, failure); return; }
			// Keep ownership through asynchronous completion too; never wait on this worker.
			future.whenComplete((value, error) -> finish(job, value, error));
		} finally {
			synchronized (this) { executing--; dispatch(); }
		}
	}

	private <T> void finish(Job<T> job, T value, Throwable error) {
		// Original ChunkStep completion updates persisted status, which selects the heightmaps
		// updated by neighboring decoration. Publish those synchronous stage callbacks before
		// releasing the footprint and starting the next conflicting original body.
		if (error == null) job.result.complete(value); else job.result.completeExceptionally(error);
		Runnable listener;
		synchronized (this) {
			active.remove(job);
			if (error == null) completed++; else failed++;
			listener = capacityListener;
			dispatch();
			if (closing && pending.isEmpty() && active.isEmpty()) executor.shutdown();
		}
		listener.run();
	}

	public synchronized Snapshot snapshot() {
		return new Snapshot(submitted, completed, failed, bypassed, pending.size(), active.size(), peakActive, peakAdmitted, closing, executing, peakExecuting);
	}

	@Override public synchronized void close() {
		closing = true;
		// Drain already admitted generation, never interrupt a partially mutating chunk.
		dispatch();
		if (pending.isEmpty() && active.isEmpty()) executor.shutdown();
	}

	public record Footprint(long minX, long minZ, long maxX, long maxZ, boolean global) {
		public Footprint {
			if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("inverted footprint");
		}
		public static Footprint region(int x, int z, int radius) {
			if (radius < 0 || radius > 32) throw new IllegalArgumentException("feature footprint radius");
			return new Footprint((long)x-radius, (long)z-radius, (long)x+radius, (long)z+radius, false);
		}
		public static Footprint serial() { return new Footprint(0, 0, 0, 0, true); }
		public boolean intersects(Footprint other) {
			return global || other.global || minX <= other.maxX && other.minX <= maxX && minZ <= other.maxZ && other.minZ <= maxZ;
		}
	}
	public record Snapshot(long submitted, long completed, long failed, long bypassed, int queued, int active,
		int peakActive, int peakAdmitted, boolean closing, int executing, int peakExecuting) {}
	private static final class Job<T> {
		final Footprint footprint;
		final Supplier<CompletableFuture<T>> body;
		final CompletableFuture<T> result = new CompletableFuture<>();
		Job(Footprint footprint, Supplier<CompletableFuture<T>> body) { this.footprint = footprint; this.body = body; }
	}
}
