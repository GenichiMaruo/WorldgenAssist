package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueuedTerrainAdmission263Test {
	@Test void admissionIsBoundedDeduplicatedAndFairAcrossOwners() {
		var a = UUID.randomUUID(); var b = UUID.randomUUID();
		var queue = new QueuedTerrainAdmission<String, String>(3);
		assertTrue(queue.offer("a1", a, "graph-a", 1, 100));
		assertTrue(queue.offer("a2", a, "graph-a", 1, 100));
		assertFalse(queue.offer("a1", a, "changed", 2, 200));
		assertTrue(queue.offer("b1", b, "graph-b", 2, 100));
		assertFalse(queue.offer("overflow", b, "graph-b", 3, 100));
		var ordered = queue.ordered(4);
		assertEquals(List.of("a1", "b1", "a2"), ordered.stream().map(QueuedTerrainAdmission.Entry::key).toList());
		assertEquals("graph-a", ordered.getFirst().context());
		assertEquals(1, ordered.getFirst().observedNanos());
		assertEquals(100, ordered.getFirst().expires());
		assertThrows(UnsupportedOperationException.class, () -> ordered.clear());
		queue.removeOwner(a);
		assertEquals(List.of("b1"), queue.ordered(5).stream().map(QueuedTerrainAdmission.Entry::key).toList());
		queue.clear(); assertEquals(0, queue.size()); assertFalse(queue.contains("b1", 6));
		assertFalse(new QueuedTerrainAdmission<String, String>(0).offer("disabled", a, "graph", 1, 2));
	}
	@Test void expirationLocalStartAndReplacementCannotRetainAnOldAdmission() {
		var owner = UUID.randomUUID(); var queue = new QueuedTerrainAdmission<String, String>(1);
		assertFalse(queue.offer("old", owner, "old-context", 10, 10));
		assertTrue(queue.offer("chunk", owner, "epoch1", 10, 20));
		var old = queue.ordered(11).getFirst();
		assertTrue(queue.contains("chunk", 19)); assertFalse(queue.contains("chunk", 20));
		assertTrue(queue.offer("chunk", owner, "epoch2", 21, 30));
		queue.remove(old); // Old server snapshot must not remove its replacement.
		assertFalse(queue.contains(old)); assertTrue(queue.contains("chunk", 22));
		queue.remove("chunk"); assertTrue(queue.ordered(23).isEmpty()); // Original local supplier began.
		assertTrue(queue.offer("expired", owner, "graph", 23, 24));
		assertTrue(queue.offer("fresh", owner, "graph", 24, 40)); // Expiry releases capacity.
		assertEquals(1, queue.size()); assertEquals("fresh", queue.ordered(25).getFirst().key());
		assertTrue(queue.ordered(40).isEmpty());
	}
}
