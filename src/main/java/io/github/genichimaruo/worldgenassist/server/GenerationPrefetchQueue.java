package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Bounded metadata only; observing a stage never schedules additional world generation. */
final class GenerationPrefetchQueue {
	private final int capacity;
	private final Map<Position, Candidate> entries = new LinkedHashMap<>();
	private final Map<UUID, Integer> ownerCounts = new LinkedHashMap<>();
	private List<Candidate> cachedOrder;
	private List<PlayerChunkDemand> cachedDemands;
	private int cachedLookahead;
	private long cachedAt, cachedUntil;
	GenerationPrefetchQueue(int capacity) { this.capacity = capacity; }

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
		if (entries.size() >= capacity) {
			// Round-robin dispatch cannot help an owner whose observations were all dropped.
			var donor = ownerCounts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
			Position displaced = null;
			if (donor.getValue() > ownerCounts.getOrDefault(owner.ownerId(), 0) + 1) {
				for (Candidate candidate : entries.values()) if (candidate.owner().equals(donor.getKey())) displaced = candidate.position();
			} else {
				long furthest=distance(position,owner);
				for (Candidate candidate:entries.values()) {
					if (!candidate.owner().equals(owner.ownerId())) continue;
					long candidateDistance=distance(candidate.position(),owner);
					if(candidateDistance>furthest) {furthest=candidateDistance;displaced=candidate.position();}
				}
				if(displaced==null) return false;
			}
			remove(displaced);
		}
		entries.put(position, new Candidate(position, owner.ownerId(), generation, ownerGeneration, expires,
			System.nanoTime(),earlyTask,dependencyTask));
		ownerCounts.merge(owner.ownerId(), 1, Integer::sum);
		cachedOrder = null;
		return true;
	}

	synchronized List<Candidate> ordered(List<PlayerChunkDemand> demands, long now) {
		return ordered(demands, now, 0);
	}
	synchronized List<Candidate> ordered(List<PlayerChunkDemand> demands, long now, int lookahead) {
		if (lookahead < 0 || lookahead > 64) throw new IllegalArgumentException("Lookahead must be 0..64");
		// Refill requests often find every owner busy. Reuse only an unchanged
		// immutable snapshot, before its first deadline and with identical demand.
		if (cachedOrder != null && cachedLookahead == lookahead && now >= cachedAt
			&& now < cachedUntil && demands.equals(cachedDemands)) return cachedOrder;
		long earliestExpiry = Long.MAX_VALUE;
		var iterator = entries.values().iterator();
		while (iterator.hasNext()) {
			Candidate candidate = iterator.next();
			if (candidate.expires() <= now || PlayerChunkDemand.selectGeneration(demands,
				candidate.position().dimension(), candidate.position().x(), candidate.position().z())
				.map(d -> !d.ownerId().equals(candidate.owner())).orElse(true)) {
				iterator.remove(); decrementOwner(candidate.owner());
			} else earliestExpiry = Math.min(earliestExpiry, candidate.expires());
		}
		List<Candidate> ordered = new ArrayList<>();
		List<List<Candidate>> owners = new ArrayList<>();
		for (PlayerChunkDemand demand : demands) {
			var nearest = entries.values().stream().filter(c -> c.owner().equals(demand.ownerId()))
				.sorted(java.util.Comparator.comparingLong(c -> distance(c.position(), demand))).toList();
			// Let the server start its immediate queue while replies for later work
			// travel. Retain every candidate and a full batch in a short queue.
			int offset = Math.min(lookahead, Math.max(0, nearest.size() - 4));
			var futureFirst = new ArrayList<Candidate>(nearest.size());
			futureFirst.addAll(nearest.subList(offset, nearest.size()));
			futureFirst.addAll(nearest.subList(0, offset));
			owners.add(futureFirst);
		}
		// Round-robin owners; every retained position is still actual observed demand.
		for (int index = 0; index < entries.size(); index++) {
			boolean found = false;
			for (List<Candidate> owner : owners) if (index < owner.size()) { ordered.add(owner.get(index)); found = true; }
			if (!found) break;
		}
		cachedDemands = List.copyOf(demands); cachedLookahead = lookahead;
		cachedAt = now; cachedUntil = earliestExpiry;
		cachedOrder = List.copyOf(ordered);
		return cachedOrder;
	}
	private static long distance(Position position, PlayerChunkDemand demand) {
		long dx = (long)position.x() - demand.chunkX(), dz = (long)position.z() - demand.chunkZ();
		return dx * dx + dz * dz;
	}
	private void decrementOwner(UUID owner) {
		ownerCounts.compute(owner, (ignored, count) -> count == 1 ? null : count - 1);
	}
	synchronized void remove(Position position) {
		Candidate removed = entries.remove(position);
		if (removed != null) { decrementOwner(removed.owner()); cachedOrder = null; }
	}
	synchronized boolean contains(Candidate candidate) { return entries.get(candidate.position()) == candidate; }
	synchronized void removeOwner(UUID owner) {
		if (entries.values().removeIf(c -> c.owner().equals(owner))) { ownerCounts.remove(owner); cachedOrder = null; }
	}
	synchronized void clear() { entries.clear(); ownerCounts.clear(); cachedOrder = null; }
	synchronized int size() { return entries.size(); }
	record Position(Identifier dimension, int x, int z) { }
	record Candidate(Position position, UUID owner, long generation, long ownerGeneration, long expires,
		long observedNanos,boolean earlyTask,boolean dependencyTask) { }
}
