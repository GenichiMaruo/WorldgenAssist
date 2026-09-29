package io.github.genichimaruo.worldgenassist.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Bounded asynchronous waiting; latency samples belong to one owner/context lifecycle. */
final class AdaptiveDemandWait {
	private final ScheduledExecutorService scheduler;
	private final LongSupplier clock;
	private final Map<UUID, Estimate> estimates = new ConcurrentHashMap<>();

	AdaptiveDemandWait(ScheduledExecutorService scheduler, LongSupplier clock) {
		this.scheduler = scheduler;
		this.clock = clock;
	}

	void recordReady(UUID owner, long elapsedNanos) {
		if (elapsedNanos <= 0) return;
		double millis = elapsedNanos / 1_000_000.0;
		estimates.compute(owner, (key, previous) -> previous == null
			? new Estimate(millis, 0, 1)
			: new Estimate(previous.mean() * .75 + millis * .25,
				previous.deviation() * .75 + Math.abs(millis - previous.mean()) * .25,
				Math.min(3, previous.samples() + 1)));
	}

	void removeOwner(UUID owner) { estimates.remove(owner); }
	void clear() { estimates.clear(); }

	static long maximumMillis(long baseMillis) { return Math.min(1000L, baseMillis * 2L); }
	private double expectedMillis(UUID owner) {
		Estimate estimate = estimates.get(owner);
		return estimate == null ? 150.0 : estimate.mean() + 2 * estimate.deviation() + 25;
	}

	boolean shouldSubmitDirect(UUID owner, long baseMillis, boolean aheadActive) {
		// A slow owner with no ahead work still needs bounded admission to get its pipeline started.
		if (!aheadActive) return true;
		Estimate estimate = estimates.get(owner);
		return estimate == null || estimate.samples() < 3 || expectedMillis(owner) <= maximumMillis(baseMillis);
	}

	long initialMillis(UUID owner, long requestStartedNanos, long baseMillis, boolean adaptive) {
		if (!adaptive) return baseMillis;
		double elapsed = Math.max(0L, clock.getAsLong() - requestStartedNanos) / 1_000_000.0;
		return Math.max(baseMillis, Math.min(maximumMillis(baseMillis),
			(long) Math.ceil(expectedMillis(owner) - elapsed)));
	}

	<T> CompletableFuture<T> await(UUID owner, long requestStartedNanos, long baseMillis,
		boolean adaptive, CompletableFuture<T> prepared, BooleanSupplier responseDecoded, Runnable expire) {
		if (prepared.isDone()) return prepared;
		long initial = initialMillis(owner, requestStartedNanos, baseMillis, adaptive);
		long maximum = adaptive ? maximumMillis(baseMillis) : baseMillis;
		var wait = new Wait<T>(prepared, responseDecoded, expire, adaptive,
			clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(maximum));
		wait.schedule(TimeUnit.MILLISECONDS.toNanos(initial));
		prepared.whenComplete(wait::complete);
		return wait.result;
	}

	private final class Wait<T> {
		private final CompletableFuture<T> result = new CompletableFuture<>();
		private final BooleanSupplier responseDecoded;
		private final Runnable expire;
		private final boolean adaptive;
		private final long hardDeadline;
		private ScheduledFuture<?> timer;
		private boolean graceUsed;

		Wait(CompletableFuture<T> prepared, BooleanSupplier responseDecoded, Runnable expire,
			boolean adaptive, long hardDeadline) {
			this.responseDecoded = responseDecoded; this.expire = expire;
			this.adaptive = adaptive; this.hardDeadline = hardDeadline;
			result.whenComplete((value, error) -> {
				cancelTimer();
				if (result.isCancelled()) prepared.cancel(false);
			});
		}

		private synchronized void cancelTimer() { if (timer != null) timer.cancel(false); }
		private void complete(T value, Throwable error) {
			if (error != null) result.completeExceptionally(error);
			else if (clock.getAsLong() - hardDeadline >= 0) expireNow();
			else result.complete(value);
		}
		private void expireNow() {
			if (result.completeExceptionally(new TimeoutException("Demand wait budget exceeded"))) expire.run();
		}
		private synchronized void schedule(long nanos) {
			if (result.isDone()) return;
			try { timer = scheduler.schedule(this::deadline, nanos, TimeUnit.NANOSECONDS); }
			catch (RuntimeException error) {
				if (result.completeExceptionally(error)) expire.run();
			}
		}
		private void deadline() {
			synchronized (this) {
				if (result.isDone()) return;
				long remaining = hardDeadline - clock.getAsLong();
				if (adaptive && !graceUsed && remaining > 0 && responseDecoded.getAsBoolean()) {
					graceUsed = true;
					schedule(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)));
					return;
				}
			}
			expireNow();
		}
	}

	private record Estimate(double mean, double deviation, int samples) { }
}
