package io.github.genichimaruo.worldgenassist.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;

public final class RemoteDensityResultCache {
	private final int capacity;
	private final Map<Key, double[]> entries;

	public RemoteDensityResultCache(int capacity) {
		if (capacity < 0 || capacity > RemoteWorldgenConfig.MAX_CACHE_ENTRIES) {
			throw new IllegalArgumentException(
				"capacity must be between 0 and " + RemoteWorldgenConfig.MAX_CACHE_ENTRIES + ": " + capacity
			);
		}
		this.capacity = capacity;
		this.entries = new LinkedHashMap<>(Math.max(1, capacity), 0.75F, true);
	}

	public synchronized Optional<double[]> get(Key key) {
		Objects.requireNonNull(key, "key");
		double[] densities = entries.get(key);
		return densities == null ? Optional.empty() : Optional.of(densities.clone());
	}

	public synchronized boolean contains(Key key) {
		return entries.containsKey(Objects.requireNonNull(key, "key"));
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
		entries.put(key, result.densities());
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
		return entries.size();
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
		int cellHeight
	) {
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
