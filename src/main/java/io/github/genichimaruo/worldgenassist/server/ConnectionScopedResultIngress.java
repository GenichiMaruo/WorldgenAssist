package io.github.genichimaruo.worldgenassist.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;

/** Immutable owner attribution, with revocation visible to queued decoder work. */
public final class ConnectionScopedResultIngress<C> {
	@FunctionalInterface public interface Receiver {
		void receive(UUID owner, TerrainJobResultPayload payload, long receivedNanos, BooleanSupplier current);
	}
	@FunctionalInterface public interface BiomeReceiver {
		void receive(UUID owner, io.github.genichimaruo.worldgenassist.network.TerrainBiomeResultPayload payload, BooleanSupplier current);
	}
	private final ConcurrentHashMap<C, Session> connections = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, Session> owners = new ConcurrentHashMap<>();

	public synchronized void bind(C connection, UUID owner, Receiver receiver) {
		bind(connection, owner, receiver, (ignoredOwner, ignoredPayload, ignoredCurrent) -> {});
	}
	public synchronized void bind(C connection, UUID owner, Receiver receiver, BiomeReceiver biomes) {
		remove(connection);
		Session previous = owners.remove(owner);
		if (previous != null) { previous.active = false; connections.values().remove(previous); }
		var session = new Session(owner, receiver, biomes);
		connections.put(connection, session);
		owners.put(owner, session);
	}
	public synchronized void remove(C connection) {
		Session previous = connections.remove(connection);
		if (previous != null) { previous.active = false; owners.remove(previous.owner, previous); }
	}
	public synchronized void clear() {
		connections.values().forEach(session -> session.active = false);
		connections.clear(); owners.clear();
	}
	public boolean receive(C connection, TerrainJobResultPayload payload, long receivedNanos) {
		Session session = connections.get(connection);
		if (session == null || !session.active) return false;
		session.receiver.receive(session.owner, payload, receivedNanos, () -> session.active);
		return true;
	}
	public boolean receive(C connection, io.github.genichimaruo.worldgenassist.network.TerrainBiomeResultPayload payload) {
		Session session = connections.get(connection);
		if (session == null || !session.active) return false;
		session.biomes.receive(session.owner, payload, () -> session.active); return true;
	}
	private static final class Session {
		final UUID owner;
		final Receiver receiver;
		final BiomeReceiver biomes;
		volatile boolean active = true;
		Session(UUID owner, Receiver receiver, BiomeReceiver biomes) { this.owner = owner; this.receiver = receiver; this.biomes = biomes; }
	}
}
