package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Bounded metadata only; observing a stage never schedules additional world generation. */
final class GenerationPrefetchQueue {
	static final int MAX_CAPACITY = 16_384;
	private final int capacity;
	private final Map<Position, Entry> entries = new LinkedHashMap<>();
	private final Map<UUID, OwnerIndex> owners = new LinkedHashMap<>();
	private final TreeMap<Expiry, Entry> expiry = new TreeMap<>();
	private final LinkedHashSet<Entry> pendingValidation = new LinkedHashSet<>();
	private List<PlayerChunkDemand> validatedDemands;
	private long sequence;
	private List<Candidate> cachedOrder;
	private List<PlayerChunkDemand> cachedDemands;
	private int cachedLookahead, cachedLimit;
	private long cachedAt, cachedUntil;
	GenerationPrefetchQueue(int capacity) {
		if (capacity < 0 || capacity > MAX_CAPACITY) throw new IllegalArgumentException("Candidate capacity must be 0..16384");
		this.capacity = capacity;
	}

	/** A hint from an existing Minecraft task; null means disk load is not known yet.
	 * This never authorizes applying output to a chunk or creating a world ticket. */
	static boolean targetsUnfinishedTerrain(net.minecraft.world.level.chunk.status.ChunkStatus requested,
		net.minecraft.world.level.chunk.status.ChunkStatus persisted) {
		return !requested.isBefore(net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN)
			&& (persisted == null || persisted.isBefore(net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN));
	}

	synchronized void offer(PlayerChunkDemand owner, int x, int z, long generation, long ownerGeneration, long expires) {
		offer(owner,x,z,generation,ownerGeneration,expires,false);
	}
	synchronized boolean offer(PlayerChunkDemand owner, int x, int z, long generation, long ownerGeneration, long expires,
		boolean earlyTask) {
		return offer(owner,x,z,generation,ownerGeneration,expires,earlyTask,false);
	}
	synchronized boolean offer(PlayerChunkDemand owner, int x, int z, long generation, long ownerGeneration, long expires,
		boolean earlyTask, boolean dependencyTask) {
		Position position = new Position(owner.dimension(), x, z);
		if (entries.containsKey(position) || capacity == 0) return false;
		OwnerIndex ownerIndex = owners.get(owner.ownerId());
		if (ownerIndex != null) ownerIndex.anchor(owner);
		if (entries.size() >= capacity) {
			// Round-robin dispatch cannot help an owner whose observations were all dropped.
			OwnerIndex donor = null;
			for (OwnerIndex index : owners.values()) if (donor == null || index.nearest.size() > donor.nearest.size()) donor = index;
			int ownerCount = ownerIndex == null ? 0 : ownerIndex.nearest.size();
			Entry displaced;
			if (donor.nearest.size() > ownerCount + 1) displaced = donor.nearest.lastEntry().getValue();
			else {
				if (ownerIndex == null || ownerIndex.nearest.lastKey().distance() <= distance(position, owner)) return false;
				displaced = ownerIndex.nearest.lastEntry().getValue();
			}
			remove(displaced.candidate.position());
		}
		ownerIndex = owners.computeIfAbsent(owner.ownerId(), ignored -> new OwnerIndex(owner));
		Entry entry = new Entry(new Candidate(position, owner.ownerId(), generation, ownerGeneration, expires,
			System.nanoTime(),earlyTask,dependencyTask), sequence++, owner);
		entries.put(position, entry);
		ownerIndex.nearest.put(entry.rank, entry);
		expiry.put(entry.expiry, entry);
		pendingValidation.add(entry);
		cachedOrder = null;
		return true;
	}

	synchronized List<Candidate> ordered(List<PlayerChunkDemand> demands, long now) {
		return ordered(demands, now, 0);
	}
	synchronized List<Candidate> ordered(List<PlayerChunkDemand> demands, long now, int lookahead) {
		return ordered(demands, now, lookahead, MAX_CAPACITY);
	}
	/** A bounded dispatch prefix; later hints stay indexed until a later refill. */
	synchronized List<Candidate> ordered(List<PlayerChunkDemand> demands, long now, int lookahead, int limit) {
		if (lookahead < 0 || lookahead > 64) throw new IllegalArgumentException("Lookahead must be 0..64");
		if (limit < 1 || limit > MAX_CAPACITY) throw new IllegalArgumentException("Dispatch limit must be 1..16384");
		// Refill requests often find every owner busy. Reuse only an unchanged
		// immutable snapshot, before its first deadline and with identical demand.
		if (cachedOrder != null && cachedLookahead == lookahead && cachedLimit == limit && now >= cachedAt
			&& now < cachedUntil && demands.equals(cachedDemands)) return cachedOrder;
		while (!expiry.isEmpty() && expiry.firstKey().expires() <= now) remove(expiry.firstEntry().getValue().candidate.position());
		boolean changed = !demands.equals(validatedDemands);
		// Only movement/participation changes require a whole-reservoir view check.
		// New entries still receive the original nearest-owner check before dispatch.
		for (Entry entry : List.copyOf(changed ? entries.values() : pendingValidation)) {
			Candidate candidate = entry.candidate;
			if (PlayerChunkDemand.selectGeneration(demands,
				candidate.position().dimension(), candidate.position().x(), candidate.position().z())
				.map(d -> !d.ownerId().equals(candidate.owner())).orElse(true)) {
				remove(candidate.position());
			}
		}
		pendingValidation.clear(); validatedDemands = List.copyOf(demands);
		List<Candidate> ordered = new ArrayList<>();
		List<Cursor> cursors = new ArrayList<>();
		for (PlayerChunkDemand demand : demands) {
			OwnerIndex index = owners.get(demand.ownerId());
			if (index != null) { index.anchor(demand); cursors.add(new Cursor(index, lookahead)); }
		}
		// Round-robin owners; every retained position is still actual observed demand.
		while (ordered.size() < limit) {
			boolean found = false;
			for (Cursor cursor : cursors) if (cursor.hasNext()) {
				ordered.add(cursor.next()); found = true;
				if (ordered.size() == limit) break;
			}
			if (!found) break;
		}
		cachedDemands = List.copyOf(demands); cachedLookahead = lookahead; cachedLimit = limit;
		cachedAt = now; cachedUntil = expiry.isEmpty() ? Long.MAX_VALUE : expiry.firstKey().expires();
		cachedOrder = List.copyOf(ordered);
		return cachedOrder;
	}
	private static long distance(Position position, PlayerChunkDemand demand) {
		long dx = (long)position.x() - demand.chunkX(), dz = (long)position.z() - demand.chunkZ();
		return dx * dx + dz * dz;
	}
	synchronized void remove(Position position) {
		Entry removed = entries.remove(position);
		if (removed != null) {
			OwnerIndex index = owners.get(removed.candidate.owner());
			index.nearest.remove(removed.rank);
			if (index.nearest.isEmpty()) owners.remove(removed.candidate.owner());
			expiry.remove(removed.expiry); pendingValidation.remove(removed); cachedOrder = null;
		}
	}
	synchronized boolean contains(Candidate candidate) {
		Entry entry = entries.get(candidate.position()); return entry != null && entry.candidate == candidate;
	}
	synchronized void removeOwner(UUID owner) {
		OwnerIndex index = owners.get(owner);
		if (index != null) for (Entry entry : List.copyOf(index.nearest.values())) remove(entry.candidate.position());
	}
	synchronized void clear() {
		entries.clear(); owners.clear(); expiry.clear(); pendingValidation.clear();
		cachedOrder = null; validatedDemands = null; sequence = 0;
	}
	synchronized int size() { return entries.size(); }
	private record Rank(long distance, long sequence) implements Comparable<Rank> {
		public int compareTo(Rank other) { int order = Long.compare(distance, other.distance); return order != 0 ? order : Long.compare(sequence, other.sequence); }
	}
	private record Expiry(long expires, long sequence) implements Comparable<Expiry> {
		public int compareTo(Expiry other) { int order = Long.compare(expires, other.expires); return order != 0 ? order : Long.compare(sequence, other.sequence); }
	}
	private static final class Entry {
		final Candidate candidate; final long sequence; final Expiry expiry; Rank rank;
		Entry(Candidate candidate, long sequence, PlayerChunkDemand demand) {
			this.candidate = candidate; this.sequence = sequence;
			this.expiry = new Expiry(candidate.expires(), sequence);
			this.rank = new Rank(distance(candidate.position(), demand), sequence);
		}
	}
	private static final class OwnerIndex {
		PlayerChunkDemand demand; final TreeMap<Rank, Entry> nearest = new TreeMap<>();
		OwnerIndex(PlayerChunkDemand demand) { this.demand = demand; }
		void anchor(PlayerChunkDemand current) {
			if (current.equals(demand)) return;
			var retained = List.copyOf(nearest.values()); nearest.clear(); demand = current;
			for (Entry entry : retained) {
				entry.rank = new Rank(distance(entry.candidate.position(), current), entry.sequence);
				nearest.put(entry.rank, entry);
			}
		}
	}
	private static final class Cursor {
		final Iterator<Entry> rest; final ArrayDeque<Entry> prefix = new ArrayDeque<>();
		Cursor(OwnerIndex index, int lookahead) {
			rest = index.nearest.values().iterator();
			int offset = Math.min(lookahead, Math.max(0, index.nearest.size() - 4));
			for (int i = 0; i < offset; i++) prefix.addLast(rest.next());
		}
		boolean hasNext() { return rest.hasNext() || !prefix.isEmpty(); }
		Candidate next() { return (rest.hasNext() ? rest.next() : prefix.removeFirst()).candidate; }
	}
	record Position(Identifier dimension, int x, int z) { }
	record Candidate(Position position, UUID owner, long generation, long ownerGeneration, long expires,
		long observedNanos,boolean earlyTask,boolean dependencyTask) { }
}
