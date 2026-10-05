package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Original consecutive message lane pauses asynchronously before a decoration burst. */
final class FeatureMessagePump implements AutoCloseable {
	private final Executor executor;
	private final FeatureStageQueue features;
	private final int reservation;
	private final Consumer<Runnable> runner;
	private final ArrayDeque<Runnable> messages = new ArrayDeque<>();
	private boolean running, closed;
	private long pauses;

	FeatureMessagePump(Executor executor, FeatureStageQueue features, int reservation, Consumer<Runnable> runner) {
		if (reservation < 1 || !features.hasCapacity(reservation)) throw new IllegalArgumentException("message reservation");
		this.executor = Objects.requireNonNull(executor);
		this.features = features;
		this.reservation = reservation;
		this.runner = Objects.requireNonNull(runner);
		features.onCapacityChanged(this::wake);
	}

	synchronized void schedule(Runnable task) {
		if (closed) throw new RejectedExecutionException("worldgen dispatcher closed");
		messages.addLast(Objects.requireNonNull(task));
		wake();
	}
	private synchronized void wake() {
		if (closed || running || messages.isEmpty()) return;
		if (!features.hasCapacity(reservation)) { pauses++; return; }
		running = true;
		try { executor.execute(this::runMessage); }
		catch (RuntimeException error) { running = false; throw error; }
	}
	private void runMessage() {
		Runnable task;
		synchronized (this) {
			if (closed) { running = false; return; }
			task = messages.removeFirst();
		}
		try { runner.accept(task); }
		finally { synchronized (this) { running = false; wake(); } }
	}
	synchronized long pauses() { return pauses; }
	synchronized int queued() { return messages.size(); }
	@Override public synchronized void close() {
		closed = true;
		messages.clear();
		features.close();
	}
}
