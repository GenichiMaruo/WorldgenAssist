package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientConnectionAdmission263Test {
	@Test void networkAdmissionWorksWithoutPumpingMainAndRetainsQueueBound() throws Exception {
		Object connection = new Object();
		var gate = new ClientConnectionAdmission<Object>(); gate.bind(connection);
		var executor = new ClientWorkExecutor(1, Thread::new);
		var started = new CountDownLatch(1); var release = new CountDownLatch(1);
		try {
			var network = new Thread(() -> assertTrue(gate.dispatch(connection,
				() -> executor.execute(() -> { started.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }))));
			network.start(); network.join(2000); assertFalse(network.isAlive());
			assertTrue(started.await(2, TimeUnit.SECONDS));
			for (int i = 0; i < 2; i++) assertTrue(gate.dispatch(connection, () -> executor.execute(() -> {})));
			assertThrows(RejectedExecutionException.class, () -> gate.dispatch(connection, () -> executor.execute(() -> {})));
		} finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
	}
	@Test void respawnFenceRejectsOldContextAndDelayedOldConnectionClose() {
		Object oldConnection = new Object(), replacement = new Object();
		var gate = new ClientConnectionAdmission<Object>(); var admitted = new AtomicInteger();
		gate.bind(oldConnection); assertTrue(gate.suspend(oldConnection));
		assertFalse(gate.dispatch(oldConnection, admitted::incrementAndGet));
		assertFalse(gate.resume(replacement)); assertTrue(gate.suspended(oldConnection));
		assertTrue(gate.resume(oldConnection)); assertTrue(gate.dispatch(oldConnection, admitted::incrementAndGet));
		gate.bind(replacement); assertFalse(gate.clear(oldConnection));
		assertFalse(gate.dispatch(oldConnection, admitted::incrementAndGet));
		assertTrue(gate.dispatch(replacement, admitted::incrementAndGet));
		assertTrue(gate.clear(replacement)); assertFalse(gate.dispatch(replacement, admitted::incrementAndGet));
		assertEquals(2, admitted.get());
	}
}
