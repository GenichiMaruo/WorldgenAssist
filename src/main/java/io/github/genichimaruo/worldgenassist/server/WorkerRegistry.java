package io.github.genichimaruo.worldgenassist.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

public final class WorkerRegistry {
	private final int serverMaxInFlightPerWorker;
	private final Map<UUID, WorkerState> workers = new HashMap<>();

	public WorkerRegistry(int serverMaxInFlightPerWorker) {
		if (serverMaxInFlightPerWorker < 1 || serverMaxInFlightPerWorker > WorkerHelloPayload.MAX_PARALLEL_JOBS) {
			throw new IllegalArgumentException(
				"serverMaxInFlightPerWorker must be between 1 and " + WorkerHelloPayload.MAX_PARALLEL_JOBS + ": " + serverMaxInFlightPerWorker
			);
		}
		this.serverMaxInFlightPerWorker = serverMaxInFlightPerWorker;
	}

	public synchronized WorkerAcceptedPayload acceptHello(UUID ownerId, WorkerHelloPayload hello, boolean remoteEnabled) {
		requireOwner(ownerId);
		Objects.requireNonNull(hello, "hello");
		if (!remoteEnabled) {
			workers.remove(ownerId);
			return rejected(WorkerAcceptedPayload.Status.REMOTE_DISABLED);
		}
		if (!hello.protocolVersion().isSupported()) {
			workers.remove(ownerId);
			return rejected(WorkerAcceptedPayload.Status.UNSUPPORTED_PROTOCOL);
		}
		if (!workers.containsKey(ownerId) && workers.size() >= 64) {
			return rejected(WorkerAcceptedPayload.Status.REMOTE_DISABLED);
		}

		int maxInFlight = Math.min(serverMaxInFlightPerWorker, hello.maxParallelJobs());
		WorkerState previous = workers.get(ownerId);
		int retainedInFlight = previous == null ? 0 : previous.inFlight;
		workers.put(ownerId, new WorkerState(maxInFlight, hello.implementationVersion(), retainedInFlight));
		return new WorkerAcceptedPayload(WorldgenProtocolVersion.CURRENT, WorkerAcceptedPayload.Status.ACCEPTED, maxInFlight);
	}

	public synchronized Optional<Lease> tryAcquireSoleWorker() {
		return tryAcquireSoleWorker(null);
	}

	public synchronized Optional<Lease> tryAcquireSoleWorker(UUID requiredOwnerId) {
		if (requiredOwnerId != null) {
			requireOwner(requiredOwnerId);
		}
		if (workers.size() != 1) {
			return Optional.empty();
		}
		Map.Entry<UUID, WorkerState> entry = workers.entrySet().iterator().next();
		if (requiredOwnerId != null && !requiredOwnerId.equals(entry.getKey())) {
			return Optional.empty();
		}
		WorkerState state = entry.getValue();
		if (state.inFlight >= state.maxInFlight) {
			return Optional.empty();
		}
		state.inFlight++;
		return Optional.of(new Lease(entry.getKey(), state.implementationVersion));
	}

	public synchronized Optional<UUID> soleWorkerOwner() {
		return workers.size() == 1 ? Optional.of(workers.keySet().iterator().next()) : Optional.empty();
	}

	public synchronized Optional<Lease> tryAcquireWorker(UUID ownerId) {
		requireOwner(ownerId);
		WorkerState state = workers.get(ownerId);
		if (state == null || state.inFlight >= state.maxInFlight) { return Optional.empty(); }
		state.inFlight++;
		return Optional.of(new Lease(ownerId, state.implementationVersion));
	}

	public synchronized Set<UUID> workerOwners() { return Set.copyOf(workers.keySet()); }

	public synchronized void release(UUID ownerId) {
		requireOwner(ownerId);
		WorkerState state = workers.get(ownerId);
		if (state != null && state.inFlight > 0) {
			state.inFlight--;
		}
	}

	public synchronized boolean remove(UUID ownerId) {
		requireOwner(ownerId);
		return workers.remove(ownerId) != null;
	}

	public synchronized void clear() {
		workers.clear();
	}

	public synchronized int workerCount() {
		return workers.size();
	}

	public synchronized int inFlight(UUID ownerId) {
		requireOwner(ownerId);
		WorkerState state = workers.get(ownerId);
		return state == null ? 0 : state.inFlight;
	}

	private static WorkerAcceptedPayload rejected(WorkerAcceptedPayload.Status status) {
		return new WorkerAcceptedPayload(WorldgenProtocolVersion.CURRENT, status, 0);
	}

	private static void requireOwner(UUID ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		if (ownerId.getMostSignificantBits() == 0L && ownerId.getLeastSignificantBits() == 0L) {
			throw new IllegalArgumentException("ownerId must not be the all-zero UUID");
		}
	}

	public record Lease(UUID ownerId, String implementationVersion) {
		public Lease {
			requireOwner(ownerId);
			Objects.requireNonNull(implementationVersion, "implementationVersion");
		}
	}

	private static final class WorkerState {
		private final int maxInFlight;
		private final String implementationVersion;
		private int inFlight;

		private WorkerState(int maxInFlight, String implementationVersion, int inFlight) {
			this.maxInFlight = maxInFlight;
			this.implementationVersion = implementationVersion;
			this.inFlight = inFlight;
		}
	}
}
