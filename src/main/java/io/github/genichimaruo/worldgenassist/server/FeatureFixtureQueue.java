package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded fixture control only. Replays identical feature admission and snapshots all chunks before SPAWN. */
public final class FeatureFixtureQueue<T> implements AutoCloseable {
	private final Set<Key> expectedFeatures, expectedSpawns, expectedTerrains;
	private final List<Key> order;
	private final Executor messages;
	private final int budget;
	private final BiFunction<FeatureStageQueue.Footprint,Supplier<CompletableFuture<T>>,CompletableFuture<T>> delegate;
	private final Consumer<Key> onStart;
	private final Consumer<Snapshot> onComplete;
	private final Map<Key,Feature<T>> waiting = new LinkedHashMap<>();
	private final Map<Key,Spawn<T>> spawns = new LinkedHashMap<>();
	private final Set<Key> received = new HashSet<>(), finished = new HashSet<>(), captured = new HashSet<>();
	private final List<CompletableFuture<T>> results = new ArrayList<>();
	private final Map<Key,CompletableFuture<T>> terrains = new LinkedHashMap<>();
	private final Map<Key,T> preparedTerrains = new LinkedHashMap<>();
	private final Map<Key,Initialization<T>> initializations = new LinkedHashMap<>();
	private int cursor, completedSpawns;
	private int completedInitializations;
	private boolean terrainsReleased;
	private boolean drainScheduled, released, closed;
	private Throwable failure;

	public FeatureFixtureQueue(Set<Key> expectedFeatures, Set<Key> expectedSpawns, List<Key> order,
		Executor messages, int budget,
		BiFunction<FeatureStageQueue.Footprint,Supplier<CompletableFuture<T>>,CompletableFuture<T>> delegate,
		Consumer<Key> onStart, Consumer<Snapshot> onComplete) {
		this(expectedFeatures, expectedSpawns, Set.of(), order, messages, budget, delegate, onStart, onComplete);
	}
	public FeatureFixtureQueue(Set<Key> expectedFeatures, Set<Key> expectedSpawns, Set<Key> expectedTerrains, List<Key> order,
		Executor messages, int budget,
		BiFunction<FeatureStageQueue.Footprint,Supplier<CompletableFuture<T>>,CompletableFuture<T>> delegate,
		Consumer<Key> onStart, Consumer<Snapshot> onComplete) {
		this.expectedFeatures = Set.copyOf(expectedFeatures); this.expectedSpawns = Set.copyOf(expectedSpawns);
		this.expectedTerrains = Set.copyOf(expectedTerrains); terrainsReleased = this.expectedTerrains.isEmpty();
		if (this.expectedFeatures.isEmpty() || this.expectedFeatures.size() > 4096 || this.expectedSpawns.isEmpty()
			|| !this.expectedFeatures.containsAll(this.expectedSpawns) || this.expectedTerrains.size() > 4096
			|| (!this.expectedTerrains.isEmpty() && !this.expectedTerrains.containsAll(this.expectedFeatures)) || budget < 1 || budget > 9)
			throw new IllegalArgumentException("fixture bounds");
		this.order = order == null ? null : List.copyOf(order);
		if (this.order != null && (this.order.size() != this.expectedFeatures.size()
			|| !Set.copyOf(this.order).equals(this.expectedFeatures))) throw new IllegalArgumentException("complete unique replay inventory required");
		this.messages = Objects.requireNonNull(messages); this.budget = budget; this.delegate = Objects.requireNonNull(delegate);
		this.onStart = Objects.requireNonNull(onStart); this.onComplete = Objects.requireNonNull(onComplete);
	}
	public CompletableFuture<T> terrain(Key key, Supplier<CompletableFuture<T>> body) {
		var result = new CompletableFuture<T>();
		synchronized (this) {
			checkOpen();
			if (!expectedTerrains.contains(key) || terrains.putIfAbsent(key, result) != null)
				throw new IllegalArgumentException("unexpected or duplicate fixture TERRAIN " + key);
			results.add(result);
		}
		try {
			Objects.requireNonNull(body.get(), "fixture TERRAIN body").whenComplete((value, error) -> {
				if (error != null) { fail(error); return; }
				if (value == null) { fail(new IllegalStateException("null fixture TERRAIN result")); return; }
				boolean ready;
				synchronized (this) {
					if (closed || failure != null) return;
					preparedTerrains.put(key, value);
					ready = preparedTerrains.size() == expectedTerrains.size();
				}
				if (ready) try { messages.execute(this::publishTerrains); } catch (Throwable cause) { fail(cause); }
			});
		} catch (Throwable error) { fail(error); }
		return result.copy();
	}
	private void publishTerrains() {
		Map<Key,T> values;
		synchronized (this) {
			if (closed || failure != null || terrainsReleased) return;
			terrainsReleased = true; values = new LinkedHashMap<>(preparedTerrains);
		}
		// All original terrain bodies have finished; no additional generation/ticket is created.
		for (var entry : values.entrySet()) terrains.get(entry.getKey()).complete(entry.getValue());
	}
	public synchronized CompletableFuture<T> initialization(Key key, FeatureStageQueue.Footprint footprint, Supplier<CompletableFuture<T>> body) {
		checkOpen();
		if (!expectedFeatures.contains(key) || initializations.containsKey(key)) throw new IllegalArgumentException("unexpected or duplicate fixture INITIALIZE_LIGHT " + key);
		var job = new Initialization<>(Objects.requireNonNull(footprint),Objects.requireNonNull(body),new CompletableFuture<T>());
		initializations.put(key,job); results.add(job.result);
		if (finished.size() == expectedFeatures.size()) scheduleInitialization(job);
		return job.result.copy();
	}
	private void scheduleInitialization(Initialization<T> job) {
		try { messages.execute(() -> {
			synchronized (this) { if (closed || failure != null) return; }
			try {
				delegate.apply(job.footprint,job.body).whenComplete((value,error) -> {
					if (error != null) { fail(error); return; }
					synchronized (this) { completedInitializations++; }
					job.result.complete(value);
					synchronized (this) { releaseIfReady(); }
				});
			} catch (Throwable error) { fail(error); }
		}); } catch (Throwable error) { fail(error); }
	}

	public CompletableFuture<T> feature(Key key, FeatureStageQueue.Footprint footprint, Supplier<CompletableFuture<T>> body) {
		var job = new Feature<>(Objects.requireNonNull(footprint), Objects.requireNonNull(body), new CompletableFuture<T>());
			synchronized (this) {
			checkOpen();
			if (!terrainsReleased) throw new IllegalStateException("fixture FEATURES before terrain barrier");
			if (!expectedFeatures.contains(key) || !received.add(key)) throw new IllegalArgumentException("unexpected or duplicate fixture feature " + key);
			results.add(job.result);
			if (order != null) { waiting.put(key, job); requestDrain(); }
		}
		if (order == null) startFeature(key, job);
		return job.result.copy(); // Observer cancellation never drops fixture completion/ownership.
	}
	private void startFeature(Key key, Feature<T> job) {
		synchronized (this) { if (closed || failure != null) return; }
		CompletableFuture<T> future;
		try {
			future = Objects.requireNonNull(delegate.apply(job.footprint, () -> {
				onStart.accept(key); return Objects.requireNonNull(job.body.get(), "fixture feature body");
			}));
		} catch (Throwable error) { fail(error); return; }
		future.whenComplete((value, error) -> {
			if (error != null) { fail(error); return; }
			job.result.complete(value);
			synchronized (this) {
				finished.add(key);
				if (finished.size() == expectedFeatures.size()) for (var initialization : initializations.values()) scheduleInitialization(initialization);
				releaseIfReady();
			}
		});
	}
	private void requestDrain() {
		if (closed || failure != null || drainScheduled || cursor == order.size() || !waiting.containsKey(order.get(cursor))) return;
		drainScheduled = true;
		try { messages.execute(this::drain); } catch (Throwable error) { drainScheduled = false; fail(error); }
	}
	private void drain() {
		List<Map.Entry<Key,Feature<T>>> batch = new ArrayList<>();
		synchronized (this) {
			if (closed || failure != null) { drainScheduled = false; return; }
			while (batch.size() < budget && cursor < order.size()) {
				Key key = order.get(cursor); Feature<T> job = waiting.remove(key);
				if (job == null) break;
				cursor++; batch.add(Map.entry(key, job));
			}
		}
		// Invoked only on the existing guarded message lane, with its normal admission reservation.
		for (var entry : batch) startFeature(entry.getKey(), entry.getValue());
		synchronized (this) { drainScheduled = false; requestDrain(); }
	}

	public synchronized CompletableFuture<T> spawn(Key key, Runnable snapshot, Supplier<CompletableFuture<T>> body) {
		checkOpen();
		if (!expectedSpawns.contains(key) || spawns.containsKey(key)) throw new IllegalArgumentException("unexpected or duplicate fixture SPAWN " + key);
		var job = new Spawn<>(Objects.requireNonNull(snapshot), Objects.requireNonNull(body), new CompletableFuture<T>());
		spawns.put(key, job); results.add(job.result); releaseIfReady();
		return job.result.copy();
	}
	private void releaseIfReady() {
		if (closed || failure != null || released || finished.size() != expectedFeatures.size() || spawns.size() != expectedSpawns.size()) return;
		if (!expectedTerrains.isEmpty() && (initializations.size() != expectedFeatures.size() || completedInitializations != expectedFeatures.size())) return;
		released = true;
		try { messages.execute(this::snapshotThenSpawn); } catch (Throwable error) { fail(error); }
	}
	private void snapshotThenSpawn() {
		List<Map.Entry<Key,Spawn<T>>> batch;
		synchronized (this) { if (closed || failure != null) return; batch = new ArrayList<>(spawns.entrySet()); }
		try {
			// No natural mob spawning/FULL postprocessing until EVERY affected snapshot has been captured.
			for (var entry : batch) {
				entry.getValue().snapshot.run();
				synchronized (this) { captured.add(entry.getKey()); }
			}
			for (var entry : batch) messages.execute(() -> startSpawn(entry.getValue()));
		} catch (Throwable error) { fail(error); }
	}
	private void startSpawn(Spawn<T> job) {
		synchronized (this) { if (closed || failure != null) return; }
		try {
			Objects.requireNonNull(job.body.get(), "fixture SPAWN body").whenComplete((value, error) -> {
				if (error != null) { fail(error); return; }
				job.result.complete(value);
				Snapshot completion = null;
				synchronized (this) { completedSpawns++; if (completedSpawns == expectedSpawns.size()) completion = snapshot(); }
				if (completion != null) {
					try { onComplete.accept(completion); } catch (Throwable cause) { fail(cause); }
				}
			});
		} catch (Throwable error) { fail(error); }
	}
	private void checkOpen() {
		if (closed || failure != null) throw new RejectedExecutionException("fixture closed or failed", failure);
	}
	private void fail(Throwable error) {
		List<CompletableFuture<T>> pending;
		synchronized (this) {
			if (failure != null) return;
			failure = Objects.requireNonNull(error); pending = new ArrayList<>(results); waiting.clear();
		}
		for (var result : pending) result.completeExceptionally(error); // Never retry a partly mutating original body.
	}
	public synchronized boolean snapshotCaptured(Key key) { return captured.contains(key); }
	public synchronized boolean awaitingSpawnCompletion() {
		return !closed && failure == null && completedSpawns < expectedSpawns.size();
	}
	public synchronized Snapshot snapshot() {
		return new Snapshot(expectedFeatures.size(), received.size(), finished.size(), expectedSpawns.size(), spawns.size(),
			captured.size(), completedSpawns, waiting.size(), order != null, failure != null, closed,
			expectedTerrains.size(), terrains.size(), preparedTerrains.size(), terrainsReleased, initializations.size(), completedInitializations);
	}
	@Override public void close() {
		boolean incomplete;
		synchronized (this) { closed = true; incomplete = completedSpawns != expectedSpawns.size(); }
		if (incomplete) fail(new RejectedExecutionException("fixture stopped before complete coverage"));
	}
	public record Key(int x, int z) {}
	public record Snapshot(int expectedFeatures, int receivedFeatures, int finishedFeatures, int expectedSpawns,
		int receivedSpawns, int capturedSnapshots, int completedSpawns, int waitingFeatures, boolean replay, boolean failed, boolean closed,
		int expectedTerrains, int receivedTerrains, int preparedTerrains, boolean terrainsReleased, int receivedInitializations, int completedInitializations) {}
	private record Feature<T>(FeatureStageQueue.Footprint footprint, Supplier<CompletableFuture<T>> body, CompletableFuture<T> result) {}
	private record Spawn<T>(Runnable snapshot, Supplier<CompletableFuture<T>> body, CompletableFuture<T> result) {}
	private record Initialization<T>(FeatureStageQueue.Footprint footprint, Supplier<CompletableFuture<T>> body, CompletableFuture<T> result) {}
}
