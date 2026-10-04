package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Single-use cache claim at execution time; never waits for a response. */
public final class RemoteDensityOpportunity {
	public enum Availability { READY, LOCAL, PENDING }
	@FunctionalInterface public interface Completion {
		void complete(RemoteDensityField field, long elapsedNanos, Throwable error);
	}
	private final Supplier<RemoteDensityField> claim;
	private final Completion completion;
	private final Supplier<Availability> availability;
	private final AtomicBoolean taken = new AtomicBoolean();
	public RemoteDensityOpportunity(Supplier<RemoteDensityField> claim, Completion completion) {
		this(claim, completion, () -> Availability.LOCAL);
	}
	public RemoteDensityOpportunity(Supplier<RemoteDensityField> claim, Completion completion, Supplier<Availability> availability) {
		this.claim = Objects.requireNonNull(claim); this.completion = Objects.requireNonNull(completion);
		this.availability = Objects.requireNonNull(availability);
	}
	public Availability availability() { return taken.get() ? Availability.LOCAL : availability.get(); }
	public RemoteDensityField take() { return taken.compareAndSet(false, true) ? claim.get() : null; }
	public void complete(RemoteDensityField field, long elapsedNanos, Throwable error) {
		completion.complete(field, elapsedNanos, error);
	}
}
