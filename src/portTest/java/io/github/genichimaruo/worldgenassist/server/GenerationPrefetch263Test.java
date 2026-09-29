package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GenerationPrefetch263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void boundedCandidatesDeduplicateAndInterleaveOwnersAndDiscardObsoleteDemand() {
		var dimension = Identifier.parse("minecraft:overworld");
		var a = new PlayerChunkDemand(UUID.randomUUID(), dimension, 0, 0, 4);
		var b = new PlayerChunkDemand(UUID.randomUUID(), dimension, 100, 100, 4);
		var queue = new GenerationPrefetchQueue(3);
		queue.offer(a, 2, 0, 0, 1, 100);
		queue.offer(a, 0, 0, 0, 1, 100);
		queue.offer(a, 0, 0, 0, 1, 100);
		queue.offer(b, 100, 100, 0, 2, 100);
		queue.offer(b, 101, 100, 0, 2, 100);
		assertEquals(3, queue.size());
		var ordered = queue.ordered(List.of(a, b), 1);
		assertEquals(List.of(a.ownerId(), b.ownerId(), a.ownerId()), ordered.stream().map(GenerationPrefetchQueue.Candidate::owner).toList());
		assertEquals(0, ordered.get(0).position().x());
		queue.remove(ordered.get(0).position()); assertFalse(queue.contains(ordered.get(0)));
		assertEquals(1, queue.ordered(List.of(b), 2).size());
		assertTrue(queue.ordered(List.of(b), 101).isEmpty());
		var saturated = new GenerationPrefetchQueue(4);
		for (int x = 0; x < 4; x++) saturated.offer(a, x, 0, 0, 1, 100);
		saturated.offer(b, 100, 100, 0, 2, 100);
		saturated.offer(b, 101, 100, 0, 2, 100);
		assertEquals(4, saturated.size());
		assertEquals(List.of(a.ownerId(), b.ownerId(), a.ownerId(), b.ownerId()),
			saturated.ordered(List.of(a, b), 1).stream().map(GenerationPrefetchQueue.Candidate::owner).toList());
	}
}
