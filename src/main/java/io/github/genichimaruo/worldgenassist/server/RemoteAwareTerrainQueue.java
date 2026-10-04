package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayDeque;
import java.util.function.Supplier;
import static io.github.genichimaruo.worldgenassist.server.RemoteDensityOpportunity.Availability;

/** Metadata only. Existing backend admission bounds the queue and owns execution. */
final class RemoteAwareTerrainQueue {
	private static final int MAX_DEFERRALS = 8;
	private final ArrayDeque<Entry> entries = new ArrayDeque<>();
	private int drainers;
	private long reordered;
	synchronized boolean offer(Runnable command, Supplier<Availability> availability, int workers) {
		entries.addLast(new Entry(command, availability));
		if (drainers >= workers) return false;
		drainers++; return true;
	}
	/** Null retires this drainer atomically with concurrent offers. Never waits for a client. */
	synchronized Runnable poll() {
		if (entries.isEmpty()) { drainers--; return null; }
		Entry first = entries.getFirst(), selected = first;
		if (first.deferrals < MAX_DEFERRALS) {
			Availability best = first.availability();
			for (Entry entry : entries) {
				Availability state = entry.availability();
				if (state.ordinal() < best.ordinal()) { selected = entry; best = state; }
				if (best == Availability.READY) break;
			}
		}
		if (selected != first) { first.deferrals++; reordered++; }
		entries.remove(selected); return selected.command;
	}
	synchronized int size() { return entries.size(); }
	synchronized long reordered() { return reordered; }
	private static final class Entry {
		final Runnable command; final Supplier<Availability> probe; int deferrals;
		Entry(Runnable command, Supplier<Availability> probe) { this.command = command; this.probe = probe; }
		Availability availability() {
			try { Availability value = probe.get(); return value == null ? Availability.LOCAL : value; }
			catch (RuntimeException ignored) { return Availability.LOCAL; } // Scheduling hints grant no authority.
		}
	}
}
