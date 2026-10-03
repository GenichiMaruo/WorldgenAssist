package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;

class GenerationLookahead263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void laterActualDemandPrecedesImmediateWorkWithoutLosingOwnerFairnessOrPositions() {
		var dimension = Identifier.parse("minecraft:overworld");
		var a = new PlayerChunkDemand(UUID.randomUUID(), dimension, 0, 0, 10);
		var b = new PlayerChunkDemand(UUID.randomUUID(), dimension, 100, 0, 10);
		var queue = new GenerationPrefetchQueue(12);
		for (int x = 0; x < 6; x++) { queue.offer(a, x, 0, 0, 1, 100); queue.offer(b, 100+x, 0, 0, 2, 100); }
		var next = queue.ordered(List.of(a,b), 1, 16);
		assertEquals(List.of(2,102,3,103,4,104,5,105,0,100,1,101), next.stream().map(c -> c.position().x()).toList());
		assertEquals(12, next.stream().map(GenerationPrefetchQueue.Candidate::position).distinct().count());
		for (int i = 0; i < next.size(); i++) assertEquals(i%2 == 0 ? a.ownerId() : b.ownerId(), next.get(i).owner());
		assertEquals(0, queue.ordered(List.of(a,b), 1, 0).getFirst().position().x());
		assertTrue(queue.ordered(List.of(a,b), 101, 16).isEmpty());
	}
	@Test void shortDemandQueueRemainsUsableAndLookaheadCannotExceedItsBound() {
		var dimension = Identifier.parse("minecraft:overworld");
		var owner = new PlayerChunkDemand(UUID.randomUUID(), dimension, 0, 0, 10);
		var queue = new GenerationPrefetchQueue(4);
		for (int x = 0; x < 4; x++) queue.offer(owner, x, 0, 0, 1, 100);
		assertEquals(List.of(0,1,2,3), queue.ordered(List.of(owner), 1, 64).stream().map(c -> c.position().x()).toList());
		assertThrows(IllegalArgumentException.class, () -> queue.ordered(List.of(owner), 1, 65));
		assertThrows(IllegalArgumentException.class, () -> queue.ordered(List.of(owner), 1, -1));
		assertTrue(queue.ordered(List.of(), 1, 16).isEmpty());
	}
}
