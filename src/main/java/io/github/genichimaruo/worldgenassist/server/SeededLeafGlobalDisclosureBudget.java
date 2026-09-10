package io.github.genichimaruo.worldgenassist.server;

/** Global, owner-independent disclosure charge used before a seeded-leaf job is issued. */
public interface SeededLeafGlobalDisclosureBudget {
	ChargeStatus tryCharge(int transcriptEntries);

	Snapshot snapshot();

	enum ChargeStatus {
		ACCEPTED,
		EXHAUSTED,
		UNAVAILABLE
	}

	record Snapshot(State state, long maximumEntries, long usedEntries, long sequence, String failureReason) {
		public Snapshot {
			java.util.Objects.requireNonNull(state, "state");
			java.util.Objects.requireNonNull(failureReason, "failureReason");
			if (maximumEntries < 1L || usedEntries < 0L || usedEntries > maximumEntries || sequence < 0L) {
				throw new IllegalArgumentException("Invalid seeded-leaf disclosure budget snapshot");
			}
			if (state != State.FAILED && !failureReason.isEmpty()) {
				throw new IllegalArgumentException("Only a failed disclosure budget may carry a failure reason");
			}
		}

		public long remainingEntries() {
			return maximumEntries - usedEntries;
		}
	}

	enum State {
		CLOSED,
		ACTIVE,
		FAILED
	}
}
