package io.github.genichimaruo.worldgenassist.client;

import java.util.concurrent.atomic.AtomicLong;

/** Correlates one settings request for a screen with a process-unique request ID. */
final class SettingsRequestTracker {
	private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong();
	private long pendingRequestId = -1L;

	long begin() {
		if (pendingRequestId >= 0L) throw new IllegalStateException("A settings request is already pending");
		while (true) {
			long candidate = NEXT_REQUEST_ID.get();
			if (candidate < 0L) throw new IllegalStateException("Settings request IDs exhausted");
			if (NEXT_REQUEST_ID.compareAndSet(candidate, candidate + 1L)) {
				pendingRequestId = candidate;
				return candidate;
			}
		}
	}

	boolean accepts(long requestId) {
		return pendingRequestId == requestId;
	}

	void clear() {
		pendingRequestId = -1L;
	}
}
