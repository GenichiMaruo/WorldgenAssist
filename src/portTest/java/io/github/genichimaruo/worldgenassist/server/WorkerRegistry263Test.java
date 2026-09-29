package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

class WorkerRegistry263Test {
	@Test
	void rampsWithinAdvertisedCapacityAndBacksOffOnFailure() {
		WorkerRegistry registry = new WorkerRegistry(4);
		UUID owner = UUID.randomUUID();
		assertEquals(2, registry.acceptHello(owner,
			new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 2, "test"), true).maxInFlightJobs());
		assertTrue(registry.tryAcquireWorker(owner).isPresent());
		assertTrue(registry.tryAcquireWorker(owner).isEmpty());
		registry.release(owner);
		registry.recordSuccess(owner);
		registry.recordSuccess(owner);
		assertTrue(registry.tryAcquireWorker(owner, true).isPresent());
		assertTrue(registry.tryAcquireWorker(owner, true).isEmpty());
		registry.release(owner);
		assertTrue(registry.tryAcquireWorker(owner).isPresent());
		assertTrue(registry.tryAcquireWorker(owner).isPresent());
		assertTrue(registry.tryAcquireWorker(owner).isEmpty());
		registry.recordFailure(owner);
		registry.release(owner);
		registry.release(owner);
		assertTrue(registry.tryAcquireWorker(owner).isPresent());
		assertTrue(registry.tryAcquireWorker(owner).isEmpty());
	}
}
