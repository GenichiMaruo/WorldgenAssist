package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded actual-stage metadata. Never sends packets or schedules generation. */
final class QueuedTerrainAdmission<K, V> {
	private final int capacity;
	private final Map<K, Entry<K, V>> entries = new LinkedHashMap<>();
	QueuedTerrainAdmission(int capacity) {
		if (capacity < 0) throw new IllegalArgumentException("Negative capacity");
		this.capacity = capacity;
	}
	synchronized boolean offer(K key, UUID owner, V context, long now, long expires) {
		Objects.requireNonNull(key); Objects.requireNonNull(owner); Objects.requireNonNull(context);
		entries.values().removeIf(entry -> entry.expires() <= now);
		if (expires <= now || entries.containsKey(key) || entries.size() >= capacity) return false;
		entries.put(key, new Entry<>(key, owner, context, now, expires));
		return true;
	}
	synchronized boolean contains(K key, long now) {
		var entry = entries.get(key);
		if (entry != null && entry.expires() <= now) { entries.remove(key); return false; }
		return entry != null;
	}
	/** Immutable owner-round-robin snapshot; a full owner cannot hide another. */
	synchronized List<Entry<K, V>> ordered(long now) {
		entries.values().removeIf(entry -> entry.expires() <= now);
		var owners = new LinkedHashMap<UUID, List<Entry<K, V>>>();
		for (var entry : entries.values()) owners.computeIfAbsent(entry.owner(), ignored -> new ArrayList<>()).add(entry);
		var result = new ArrayList<Entry<K, V>>(entries.size());
		for (int index = 0; index < entries.size(); index++) {
			boolean found = false;
			for (var owner : owners.values()) if (index < owner.size()) { result.add(owner.get(index)); found = true; }
			if (!found) break;
		}
		return List.copyOf(result);
	}
	synchronized boolean contains(Entry<K, V> entry) { return entries.get(entry.key()) == entry; }
	synchronized void remove(Entry<K, V> entry) {
		if (entries.get(entry.key()) == entry) entries.remove(entry.key());
	}
	synchronized void remove(K key) { entries.remove(key); }
	synchronized void removeOwner(UUID owner) { entries.values().removeIf(entry -> entry.owner().equals(owner)); }
	synchronized void clear() { entries.clear(); }
	synchronized int size() { return entries.size(); }
	record Entry<K, V>(K key, UUID owner, V context, long observedNanos, long expires) { }
}
