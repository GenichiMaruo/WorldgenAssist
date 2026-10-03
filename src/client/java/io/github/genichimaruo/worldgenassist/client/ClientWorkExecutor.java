package io.github.genichimaruo.worldgenassist.client;

import java.util.concurrent.*;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

/** Bounded handoff slack for workers that have sent a result but not returned. */
final class ClientWorkExecutor extends ThreadPoolExecutor {
	private final int window;
	ClientWorkExecutor(int workers, ThreadFactory factory) {
		this(workers, configuredWindow(workers), factory);
	}
	ClientWorkExecutor(int workers, int window, ThreadFactory factory) {
		// Already-sent workers may still be returning when a fresh complete
		// advertised window arrives. Queue capacity includes that handoff slack.
		super(workers, workers, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(checkedWindow(window)), factory, new AbortPolicy());
		this.window = window;
	}
	private static int checkedWindow(int window) {
		if (window < 1 || window > WorkerHelloPayload.MAX_PARALLEL_JOBS) throw new IllegalArgumentException("Client job window must be 1..64");
		return window;
	}
	private static int configuredWindow(int workers) {
		String configured = System.getProperty("worldgen_assist.client.job_window");
		if (configured == null) configured = System.getenv("WORLDGEN_ASSIST_CLIENT_JOB_WINDOW");
		return checkedWindow(configured == null ? Math.min(64, workers * 2) : Integer.parseInt(configured));
	}
	int advertisedJobs() { return window; }
}
