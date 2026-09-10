package io.github.genichimaruo.worldgenassist.server;

import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;

/** Process-local fallback used by isolated authority tests and non-runtime construction. */
final class SeededLeafVolatileDisclosureBudget implements SeededLeafGlobalDisclosureBudget {
	private final long maximumEntries;
	private long usedEntries;
	private long sequence;

	SeededLeafVolatileDisclosureBudget(long maximumEntries) {
		if (maximumEntries < 1L) {
			throw new IllegalArgumentException("maximumEntries must be positive: " + maximumEntries);
		}
		this.maximumEntries = maximumEntries;
	}

	@Override
	public synchronized ChargeStatus tryCharge(int transcriptEntries) {
		if (transcriptEntries < 1 || transcriptEntries > SeededLeafTranscript.MAX_ENTRIES) {
			throw new IllegalArgumentException("Invalid transcript entry charge: " + transcriptEntries);
		}
		if (transcriptEntries > maximumEntries - usedEntries) {
			return ChargeStatus.EXHAUSTED;
		}
		usedEntries = Math.addExact(usedEntries, transcriptEntries);
		sequence = Math.incrementExact(sequence);
		return ChargeStatus.ACCEPTED;
	}

	@Override
	public synchronized Snapshot snapshot() {
		return new Snapshot(State.ACTIVE, maximumEntries, usedEntries, sequence, "");
	}
}
