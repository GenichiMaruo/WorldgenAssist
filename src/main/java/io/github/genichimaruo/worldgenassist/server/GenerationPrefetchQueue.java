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
	GenerationPrefetchQueue(int capacity) { this.capacity = capacity; }

	synchronized void offer(PlayerChunkDemand owner, int x, int z, long generation, long ownerGeneration, long expires) {
		Position position = new Position(owner.dimension(), x, z);
		if (entries.containsKey(position) || capacity == 0) return;
		if (entries.size() >= capacity) {
			// Round-robin dispatch cannot help an owner whose observations were all dropped.
			Map<UUID, Integer> counts = new java.util.HashMap<>();
			for (Candidate candidate : entries.values()) counts.merge(candidate.owner(), 1, Integer::sum);
			var donor = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
			Position displaced = null;
			if (donor.getValue() > counts.getOrDefault(owner.ownerId(), 0) + 1) {
				for (Candidate candidate : entries.values()) if (candidate.owner().equals(donor.getKey())) displaced = candidate.position();
			} else {
				long furthest=distance(position,owner);
				for (Candidate candidate:entries.values()) {
					if (!candidate.owner().equals(owner.ownerId())) continue;
					long candidateDistance=distance(candidate.position(),owner);
					if(candidateDistance>furthest) {furthest=candidateDistance;displaced=candidate.position();}
				}
				if(displaced==null) return;
			}
			entries.remove(displaced);
		}
		entries.put(position, new Candidate(position, owner.ownerId(), generation, ownerGeneration, expires));
	}

	synchronized List<Candidate> ordered(List<PlayerChunkDemand> demands, long now) {
		return ordered(demands, now, 0);
	}
	synchronized List<Candidate> ordered(List<PlayerChunkDemand> demands, long now, int lookahead) {
		if (lookahead < 0 || lookahead > 64) throw new IllegalArgumentException("Lookahead must be 0..64");
		entries.values().removeIf(candidate -> candidate.expires() <= now ||
			PlayerChunkDemand.selectGeneration(demands, candidate.position().dimension(), candidate.position().x(), candidate.position().z())
				.map(d -> !d.ownerId().equals(candidate.owner())).orElse(true));
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
		return ordered;
	}
	private static long distance(Position position, PlayerChunkDemand demand) {
		long dx = (long)position.x() - demand.chunkX(), dz = (long)position.z() - demand.chunkZ();
		return dx * dx + dz * dz;
	}
	synchronized void remove(Position position) { entries.remove(position); }
	synchronized boolean contains(Candidate candidate) { return entries.get(candidate.position()) == candidate; }
	synchronized void removeOwner(UUID owner) { entries.values().removeIf(c -> c.owner().equals(owner)); }
	synchronized void clear() { entries.clear(); }
	synchronized int size() { return entries.size(); }
	record Position(Identifier dimension, int x, int z) { }
	record Candidate(Position position, UUID owner, long generation, long ownerGeneration, long expires) { }
}
