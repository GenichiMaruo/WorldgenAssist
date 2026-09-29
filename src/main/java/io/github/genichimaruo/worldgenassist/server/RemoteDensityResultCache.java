package io.github.genichimaruo.worldgenassist.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;

public final class RemoteDensityResultCache {
	private final int capacity;
	private final Map<Key, Entry> entries;
	private final long maxAgeNanos;

	public RemoteDensityResultCache(int capacity) {
		this(capacity, Long.MAX_VALUE);
	}

	RemoteDensityResultCache(int capacity, long maxAgeNanos) {
		if (capacity < 0 || capacity > RemoteWorldgenConfig.MAX_CACHE_ENTRIES) {
			throw new IllegalArgumentException(
				"capacity must be between 0 and " + RemoteWorldgenConfig.MAX_CACHE_ENTRIES + ": " + capacity
			);
		}
		this.capacity = capacity;
		this.maxAgeNanos = maxAgeNanos;
		this.entries = new LinkedHashMap<>(Math.max(1, capacity), 0.75F, true);
	}

	public synchronized Optional<double[]> get(Key key) {
		Objects.requireNonNull(key, "key");
		pruneExpired();
		Entry entry = entries.get(key);
		return entry == null ? Optional.empty() : Optional.of(entry.result().densities());
	}

	public synchronized boolean contains(Key key) {
		pruneExpired();
		return entries.containsKey(Objects.requireNonNull(key, "key"));
	}

	public synchronized Optional<double[]> take(Key key) {
		pruneExpired();
		Entry value = entries.remove(Objects.requireNonNull(key));
		return value == null ? Optional.empty() : Optional.of(value.result().densities());
	}
	/** Consumes the density while retaining the server-issued request identity for application tracing. */
	public synchronized Optional<TerrainDensityResult> takeResult(Key key) {
		pruneExpired();
		Entry value = entries.remove(Objects.requireNonNull(key));
		return value == null ? Optional.empty() : Optional.of(value.result());
	}
	public synchronized void removeMatching(java.util.function.Predicate<Key> predicate) {
		entries.keySet().removeIf(predicate);
	}

	public synchronized boolean remove(Key key) {
		return entries.remove(Objects.requireNonNull(key, "key")) != null;
	}

	public synchronized void put(Key key, TerrainDensityResult result) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(result, "result");
		if (capacity == 0) {
			return;
		}
		if (result.densityCount() != key.sampleCount()) {
			throw new IllegalArgumentException(
				"Density result count does not match cache key shape: expected=" + key.sampleCount()
					+ " actual=" + result.densityCount()
			);
		}
		TerrainJobIdentity identity = result.identity();
		if (!identity.dimension().equals(key.dimension()) || identity.chunkX() != key.chunkX()
			|| identity.chunkZ() != key.chunkZ() || !identity.contextFingerprint().equals(key.contextFingerprint())) {
			throw new IllegalArgumentException("Density identity does not match cache key");
		}
		pruneExpired();
		entries.put(key, new Entry(result, System.nanoTime()));
		while (entries.size() > capacity) {
			entries.remove(entries.keySet().iterator().next());
		}
	}

	public synchronized int clear() {
		int removed = entries.size();
		entries.clear();
		return removed;
	}

	public synchronized int size() {
		pruneExpired();
		return entries.size();
	}

	private void pruneExpired() {
		long now = System.nanoTime();
		entries.values().removeIf(entry -> now - entry.storedNanos() >= maxAgeNanos);
	}
	private record Entry(TerrainDensityResult result, long storedNanos) { }

	public synchronized void removeOwner(UUID ownerId) {
		entries.keySet().removeIf(key -> ownerId.equals(key.ownerId()));
	}

	public record Key(
		long generation,
		Identifier dimension,
		int chunkX,
		int chunkZ,
		WorldgenContextFingerprint contextFingerprint,
		Identifier noiseSettings,
		int minY,
		int height,
		int cellWidth,
		int cellHeight,
		UUID ownerId,
		long ownerGeneration
	) {
		public Key(long generation, Identifier dimension, int chunkX, int chunkZ, WorldgenContextFingerprint contextFingerprint,
			Identifier noiseSettings, int minY, int height, int cellWidth, int cellHeight) {
			this(generation, dimension, chunkX, chunkZ, contextFingerprint, noiseSettings, minY, height, cellWidth, cellHeight, null, 0L);
		}
		public Key(long generation, Identifier dimension, int chunkX, int chunkZ, WorldgenContextFingerprint contextFingerprint,
			Identifier noiseSettings, int minY, int height, int cellWidth, int cellHeight, UUID ownerId) {
			this(generation, dimension, chunkX, chunkZ, contextFingerprint, noiseSettings, minY, height, cellWidth, cellHeight, ownerId, 0L);
		}
		public Key {
			if (generation < 0L) {
				throw new IllegalArgumentException("Cache generation must not be negative: " + generation);
			}
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(contextFingerprint, "contextFingerprint");
			Objects.requireNonNull(noiseSettings, "noiseSettings");
			if (height <= 0 || height > TerrainDensityJob.MAX_HEIGHT) {
				throw new IllegalArgumentException("Invalid cache-key height: " + height);
			}
			if (cellWidth <= 0 || cellWidth > TerrainDensityJob.CHUNK_SIDE || TerrainDensityJob.CHUNK_SIDE % cellWidth != 0) {
				throw new IllegalArgumentException("Invalid cache-key cell width: " + cellWidth);
			}
			if (cellHeight <= 0 || height % cellHeight != 0 || Math.floorMod(minY, cellHeight) != 0) {
				throw new IllegalArgumentException("Invalid cache-key vertical geometry");
			}
			Math.addExact(minY, height);
		}

		public int sampleCount() {
			return Math.multiplyExact(TerrainDensityJob.CHUNK_SIDE * TerrainDensityJob.CHUNK_SIDE, height);
		}
	}
}
