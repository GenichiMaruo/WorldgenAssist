package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ClientConnectionIngress263Test {
	@Test void capturedRequestStartsWithoutMainAndNeverRevivesOldAdmissions() throws Exception {
		var ingress = new ClientConnectionIngress<Object, String>();
		Object old = new Object(), replacement = new Object();
		List<BooleanSupplier> admitted = new ArrayList<>();
		List<String> threads = new ArrayList<>();
		int[] closed = {0}, suspended = {0};
		boolean[] restore = {false};
		var receiver = new ClientConnectionIngress.Receiver<String>() {
			@Override public boolean receive(String payload, long nanos, BooleanSupplier current) {
				assertEquals("job", payload); assertEquals(123, nanos);
				admitted.add(current); threads.add(Thread.currentThread().getName()); return true;
			}
			@Override public void suspend() { suspended[0]++; }
			@Override public boolean resume() { return restore[0]; }
			@Override public void close() { closed[0]++; }
		};
		ingress.bind(old, receiver);
		var network = new Thread(() -> assertTrue(ingress.receive(old, "job", 123)), "network-without-main");
		network.start(); network.join(2000); assertFalse(network.isAlive());
		assertEquals(List.of("network-without-main"), threads);
		assertTrue(admitted.getFirst().getAsBoolean());
		ingress.suspend(old); assertEquals(1, suspended[0]);
		assertFalse(admitted.getFirst().getAsBoolean());
		assertFalse(ingress.receive(old, "job", 123)); assertFalse(ingress.resume(old));
		assertFalse(ingress.resume(replacement));
		restore[0] = true; assertTrue(ingress.resume(old));
		assertFalse(admitted.getFirst().getAsBoolean()); // Old queued callbacks remain revoked after resume.
		assertTrue(ingress.receive(old, "job", 123));
		assertTrue(admitted.getLast().getAsBoolean());
		// Two respawns can be read before either original MAIN callback runs.
		var priorTransition = admitted.getLast();
		ingress.suspend(old); ingress.suspend(old); assertEquals(2, suspended[0]);
		assertFalse(ingress.resume(old)); assertFalse(ingress.receive(old, "job", 123));
		assertFalse(priorTransition.getAsBoolean());
		assertTrue(ingress.resume(old)); assertFalse(priorTransition.getAsBoolean());
		assertTrue(ingress.receive(old, "job", 123));
		ingress.bind(replacement, receiver); assertEquals(1, closed[0]);
		assertFalse(admitted.getLast().getAsBoolean()); assertFalse(ingress.receive(old, "job", 123));
		ingress.remove(old); assertTrue(ingress.receive(replacement, "job", 123));
		assertEquals(1, closed[0]); assertTrue(admitted.getLast().getAsBoolean());
		ingress.remove(replacement); assertEquals(2, closed[0]);
		assertFalse(admitted.getLast().getAsBoolean()); assertFalse(ingress.receive(replacement, "job", 123));
		ingress.clear(); assertEquals(2, closed[0]);
	}
}
