package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Single-use cache claim at execution time; never waits for a response. */
public final class RemoteDensityOpportunity {
	@FunctionalInterface public interface Completion {
		void complete(RemoteDensityField field, long elapsedNanos, Throwable error);
	}
	private final Supplier<RemoteDensityField> claim;
	private final Completion completion;
	private final AtomicBoolean taken = new AtomicBoolean();
	public RemoteDensityOpportunity(Supplier<RemoteDensityField> claim, Completion completion) {
		this.claim = Objects.requireNonNull(claim); this.completion = Objects.requireNonNull(completion);
	}
	public RemoteDensityField take() { return taken.compareAndSet(false, true) ? claim.get() : null; }
	public void complete(RemoteDensityField field, long elapsedNanos, Throwable error) {
		completion.complete(field, elapsedNanos, error);
	}
}
