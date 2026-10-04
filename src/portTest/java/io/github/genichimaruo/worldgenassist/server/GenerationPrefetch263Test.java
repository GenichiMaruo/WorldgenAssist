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
	@Test void taskHintsVisitOnlyTheExistingTerrainLayerAndStopAtCancellation() {
		var center = new net.minecraft.world.level.ChunkPos(40, -20);
		var claims = net.minecraft.util.StaticCache2D.create(center.x(), center.z(), 12,
			(x,z) -> new net.minecraft.world.level.ChunkPos(x,z));
		var seen = new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();
		var terrain = net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN;
		TerrainTaskHints.visit(center,net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES,claims,()->false,seen::add);
		assertTrue(seen.isEmpty());
		TerrainTaskHints.visit(center,terrain,claims,()->false,seen::add);
		assertEquals(List.of(center),seen);
		seen.clear();
		TerrainTaskHints.visit(center,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,claims,()->false,seen::add);
		// Pinned 26.3: FEATURES requires TERRAIN radius1; LIGHT adds another1.
		assertEquals(25,seen.size());
		assertEquals(25,new java.util.HashSet<>(seen).size());
		assertTrue(seen.stream().allMatch(pos -> pos.getChessboardDistance(center.x(),center.z()) <= 2));
		seen.clear();
		TerrainTaskHints.visit(center,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,claims,
			()->seen.size()>=3,seen::add);
		assertEquals(3,seen.size());
		seen.clear();
		TerrainTaskHints.visit(center,terrain,claims,()->true,seen::add);
		assertTrue(seen.isEmpty());
	}
	@Test void cachedOrderingStillExpiresReassignsAndBalancesAfterEveryRemoval() {
		var dimension=Identifier.parse("minecraft:overworld");
		var a=new PlayerChunkDemand(UUID.randomUUID(),dimension,0,0,4);
		var b=new PlayerChunkDemand(UUID.randomUUID(),dimension,100,100,4);
		var demands=List.of(a,b);
		var queue=new GenerationPrefetchQueue(4);
		queue.offer(a,0,0,0,1,20,true,true);
		queue.offer(a,1,0,0,1,100);
		queue.offer(b,100,100,0,2,100);
		var first=queue.ordered(demands,1);
		assertTrue(first.getFirst().dependencyTask());
		assertSame(first,queue.ordered(demands,2));
		queue.offer(a,0,0,0,1,200); // Duplicate must not extend lifetime.
		assertSame(first,queue.ordered(demands,3));
		assertNotSame(first,queue.ordered(demands,4,1));
		assertNotSame(first,queue.ordered(demands,4,0));
		assertThrows(UnsupportedOperationException.class,()->first.clear());
		assertEquals(2,queue.ordered(demands,20).size());
		queue.removeOwner(b.ownerId());
		for(int x=2;x<=4;x++) queue.offer(a,x,0,0,1,100);
		queue.offer(b,100,100,0,2,100);
		queue.offer(b,101,100,0,2,100);
		assertEquals(List.of(a.ownerId(),b.ownerId(),a.ownerId(),b.ownerId()),
			queue.ordered(demands,21).stream().map(GenerationPrefetchQueue.Candidate::owner).toList());
		var moved=new PlayerChunkDemand(a.ownerId(),dimension,100,100,4);
		assertTrue(queue.ordered(List.of(moved),22).isEmpty());
		queue.clear(); queue.offer(b,100,100,0,2,100);
		assertEquals(1,queue.ordered(List.of(b),23).size());
		var candidate=queue.ordered(List.of(b),23).getFirst();
		queue.remove(candidate.position());
		assertFalse(queue.contains(candidate));
		assertTrue(queue.ordered(List.of(b),24).isEmpty());
	}
	@Test void earlyRequestsExcludeKnownTerrainAndWorkThatNeverNeedsTerrain() {
		var terrain=net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN;
		var empty=net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY;
		var biomes=net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES;
		var full=net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
		assertFalse(GenerationPrefetchQueue.targetsUnfinishedTerrain(empty,null));
		assertFalse(GenerationPrefetchQueue.targetsUnfinishedTerrain(biomes,empty));
		assertTrue(GenerationPrefetchQueue.targetsUnfinishedTerrain(terrain,null));
		assertTrue(GenerationPrefetchQueue.targetsUnfinishedTerrain(full,biomes));
		assertFalse(GenerationPrefetchQueue.targetsUnfinishedTerrain(terrain,terrain));
		assertFalse(GenerationPrefetchQueue.targetsUnfinishedTerrain(full,full));
		var owner=new PlayerChunkDemand(UUID.randomUUID(),Identifier.parse("minecraft:overworld"),0,0,4);
		var queue=new GenerationPrefetchQueue(1);
		queue.offer(owner,0,0,3,7,100,true);
		var early=queue.ordered(List.of(owner),1).getFirst();
		queue.offer(owner,0,0,3,7,200);
		assertSame(early,queue.ordered(List.of(owner),2).getFirst());
		assertTrue(early.earlyTask()); // Later load observation cannot erase the original task hint.
	}
	@Test void observedTerrainDependenciesStayBoundedToTheirNearestOwner() {
		var dim = Identifier.parse("minecraft:overworld");
		var a = new PlayerChunkDemand(UUID.randomUUID(), dim, 0, 0, 10);
		var b = new PlayerChunkDemand(UUID.randomUUID(), dim, 40, 0, 10);
		int margin = net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN)
			- net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING);
		assertEquals(4, margin);
		assertFalse(a.includes(dim, 14, 14)); assertTrue(a.includesGeneration(dim, 14, 14));
		assertFalse(a.includesGeneration(dim, 15, 0)); assertFalse(a.includesGeneration(Identifier.parse("minecraft:the_nether"), 0, 0));
		assertEquals(a, PlayerChunkDemand.selectGeneration(List.of(a,b),dim,14,14).orElseThrow());
		assertEquals(b, PlayerChunkDemand.selectGeneration(List.of(a,b),dim,26,0).orElseThrow());
		assertTrue(PlayerChunkDemand.selectGeneration(List.of(a,b),dim,20,0).isEmpty());
		var queue = new GenerationPrefetchQueue(2); queue.offer(a,14,14,0,1,100);
		assertEquals(1,queue.ordered(List.of(a),1).size()); assertTrue(queue.ordered(List.of(b),1).isEmpty());
		var maxView = new PlayerChunkDemand(a.ownerId(),dim,0,0,32);
		assertTrue(maxView.includesGeneration(dim,36,36)); assertFalse(maxView.includesGeneration(dim,37,0));
	}
	@Test void nearerHintReplacesOnlyItsOwnersFartherHintAtBalancedCapacity() {
		var dimension=Identifier.parse("minecraft:overworld");
		var a=new PlayerChunkDemand(UUID.randomUUID(),dimension,0,0,10);
		var b=new PlayerChunkDemand(UUID.randomUUID(),dimension,100,100,10);
		var queue=new GenerationPrefetchQueue(4);
		queue.offer(a,6,0,0,1,100);queue.offer(a,4,0,0,1,100);
		queue.offer(b,100,100,0,2,100);queue.offer(b,101,100,0,2,100);
		queue.offer(a,0,0,0,1,100);
		queue.offer(b,108,100,0,2,100);
		assertEquals(4,queue.size());
		var ordered=queue.ordered(List.of(a,b),1);
		assertEquals(List.of(0,100,4,101),ordered.stream().map(c->c.position().x()).toList());
		assertEquals(List.of(a.ownerId(),b.ownerId(),a.ownerId(),b.ownerId()),ordered.stream().map(GenerationPrefetchQueue.Candidate::owner).toList());
		assertTrue(queue.ordered(List.of(a,b),101).isEmpty());
	}
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
