package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;
import org.junit.jupiter.api.Test;

class WorkerRegistryTest {
	private static final UUID OWNER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OWNER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Test
	void acceptsSupportedWorkerAndEnforcesLeaseCapacity() {
		WorkerRegistry registry = new WorkerRegistry(2);
		WorkerAcceptedPayload response = registry.acceptHello(
			OWNER_A,
			new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 1, "test-worker"),
			true
		);

		assertTrue(response.accepted());
		assertEquals(1, response.maxInFlightJobs());
		assertEquals(OWNER_A, registry.soleWorkerOwner().orElseThrow());
		assertTrue(registry.tryAcquireSoleWorker(OWNER_B).isEmpty());
		assertEquals(OWNER_A, registry.tryAcquireSoleWorker().orElseThrow().ownerId());
		assertTrue(registry.tryAcquireSoleWorker().isEmpty());
		assertEquals(1, registry.inFlight(OWNER_A));

		registry.release(OWNER_A);
		assertTrue(registry.tryAcquireSoleWorker().isPresent());
	}

	@Test
	void rejectsDisabledAndUnsupportedWorkers() {
		WorkerRegistry registry = new WorkerRegistry(1);

		assertEquals(
			WorkerAcceptedPayload.Status.REMOTE_DISABLED,
			registry.acceptHello(OWNER_A, hello(WorldgenProtocolVersion.CURRENT), false).status()
		);
		assertEquals(
			WorkerAcceptedPayload.Status.UNSUPPORTED_PROTOCOL,
			registry.acceptHello(OWNER_A, hello(new WorldgenProtocolVersion(3)), true).status()
		);
		assertEquals(0, registry.workerCount());
	}

	@Test
	void refusesAmbiguousCrossPlayerSelectionAndRemovesDisconnects() {
		WorkerRegistry registry = new WorkerRegistry(1);
		registry.acceptHello(OWNER_A, hello(WorldgenProtocolVersion.CURRENT), true);
		registry.acceptHello(OWNER_B, hello(WorldgenProtocolVersion.CURRENT), true);

		assertTrue(registry.soleWorkerOwner().isEmpty());
		assertTrue(registry.tryAcquireSoleWorker().isEmpty());
		assertTrue(registry.remove(OWNER_B));
		assertFalse(registry.remove(OWNER_B));
		assertEquals(OWNER_A, registry.tryAcquireSoleWorker().orElseThrow().ownerId());
	}

	private static WorkerHelloPayload hello(WorldgenProtocolVersion version) {
		return new WorkerHelloPayload(version, 1, "test-worker");
	}
}
