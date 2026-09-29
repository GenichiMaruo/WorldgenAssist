package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;
import org.junit.jupiter.api.Test;

class ClientReplySession263Test {
	@Test void workerCanSendWithoutPumpingClientMainThread() throws Exception {
		List<String> threads = new ArrayList<>();
		var replies = new ClientReplySession(payload -> threads.add(Thread.currentThread().getName()));
		var worker = new Thread(() -> assertTrue(replies.send(hello(), () -> true)), "test-worker");
		worker.start(); worker.join(2000);
		assertFalse(worker.isAlive());
		assertEquals(List.of("test-worker"), threads);
	}
	@Test void cancellationAndRevocationCannotSendToReplacementConnection() {
		List<Object> oldConnection = new ArrayList<>(), newConnection = new ArrayList<>();
		var oldSession = new ClientReplySession(oldConnection::add);
		var cancelled = new AtomicBoolean(true);
		assertFalse(oldSession.send(hello(), () -> !cancelled.get()));
		oldSession.close();
		var replacement = new ClientReplySession(newConnection::add);
		assertFalse(oldSession.send(hello(), () -> true));
		assertTrue(replacement.send(hello(), () -> true));
		assertTrue(oldConnection.isEmpty()); assertEquals(1, newConnection.size());
	}
	private static WorkerHelloPayload hello() { return new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 1, "test"); }
}
