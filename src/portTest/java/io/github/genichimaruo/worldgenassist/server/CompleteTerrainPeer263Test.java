package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.github.genichimaruo.worldgenassist.common.*;
import net.minecraft.resources.Identifier;

class CompleteTerrainPeer263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static TerrainDensityJob job() {
		return new TerrainDensityJob(new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(),
			Identifier.withDefaultNamespace("overworld"), 1, -2, WorldgenContextFingerprint.fromBytes(new byte[32])),
			8675309, false, Identifier.withDefaultNamespace("overworld"), -64, 16, 1, 1, TerrainWorkKind.COMPLETE_TERRAIN);
	}
	private static CompleteTerrainData data(int changed) {
		byte[] blocks = new byte[4096], digest = new byte[32]; short[] surface = new short[256], floor = new short[256];
		short[] offsets = {12, 1, 12};
		if (changed == 1) blocks[200] = 1;
		if (changed == 2) surface[200] = 1;
		if (changed == 3) floor[200] = 1;
		if (changed == 4) offsets = new short[]{1, 12, 12};
		if (changed == 5) digest[31] = 1;
		return new CompleteTerrainData(-64, 16, blocks, surface, floor, new short[][]{offsets}, digest);
	}
	private static RemoteJobCoordinator.Submission submission(UUID owner, TerrainDensityJob job, CompletableFuture<TerrainDensityResult> result) {
		return new RemoteJobCoordinator.Submission(owner, job, result);
	}
	@Test void agreementRequiresDistinctAssignmentsAndEveryTerrainComponentAndFailsPromptly() {
		var a = job(); var b = job(); UUID ownerA = UUID.randomUUID(), ownerB = UUID.randomUUID();
		for (int changed = 0; changed <= 5; changed++) {
			var first = new CompletableFuture<TerrainDensityResult>(); var second = new CompletableFuture<TerrainDensityResult>();
			AtomicInteger mismatches = new AtomicInteger();
			var agreed = CompleteTerrainPeerVerifier.agree(submission(ownerA, a, first), submission(ownerB, b, second),
				() -> true, () -> {}, error -> mismatches.incrementAndGet());
			first.complete(TerrainDensityResult.fromCompleteTerrain(a.identity(), data(0), 0)); assertFalse(agreed.isDone());
			second.complete(TerrainDensityResult.fromCompleteTerrain(b.identity(), data(changed), 0));
			if (changed == 0) { assertTrue(agreed.join().hasPeerVerification()); assertEquals(0, mismatches.get()); }
			else {
				var failure = assertThrows(java.util.concurrent.CompletionException.class, agreed::join);
				String[] fields = {"equal","blocks=","surface=","floor=","postprocessing_sections=","biome_digest=different"};
				assertTrue(failure.getCause().getMessage().contains(fields[changed]));
				assertEquals(1, mismatches.get());
			}
		}
		var first = new CompletableFuture<TerrainDensityResult>(); var second = new CompletableFuture<TerrainDensityResult>();
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainPeerVerifier.agree(submission(ownerA, a, first), submission(ownerA, b, second), () -> true, () -> {}, error -> {}));
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainPeerVerifier.agree(submission(ownerA, a, first), submission(ownerB, a, second), () -> true, () -> {}, error -> {}));
		var wrongSeed = new TerrainDensityJob(b.identity(), 1, b.generateStructures(), b.noiseSettings(), b.minY(), b.height(), 1, 1, b.workKind());
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainPeerVerifier.agree(submission(ownerA, a, first), submission(ownerB, wrongSeed, second), () -> true, () -> {}, error -> {}));
		var box = new TerrainBeardifierData.Box(0, 50, 0, 10, 70, 10);
		var shape = new TerrainBeardifierData(java.util.List.of(new TerrainBeardifierData.Rigid(box, 2, 0)), java.util.List.of(), box);
		var wrongShape = new TerrainDensityJob(b.identity(), b.worldSeed(), b.generateStructures(), b.noiseSettings(), b.minY(), b.height(), 1, 1, b.workKind(), shape);
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainPeerVerifier.agree(submission(ownerA, a, first), submission(ownerB, wrongShape, second), () -> true, () -> {}, error -> {}));
		var failed = CompleteTerrainPeerVerifier.agree(submission(ownerA, a, first), submission(ownerB, b, second), () -> true, () -> {}, error -> fail("Late comparison"));
		second.completeExceptionally(new IllegalStateException("Disconnected")); assertTrue(failed.isCompletedExceptionally());
		first.complete(TerrainDensityResult.fromCompleteTerrain(a.identity(), data(0), 0)); assertTrue(failed.isCompletedExceptionally());
		var cancelledFirst = new CompletableFuture<TerrainDensityResult>(); var cancelledSecond = new CompletableFuture<TerrainDensityResult>();
		var cancelled = CompleteTerrainPeerVerifier.agree(submission(ownerA, a, cancelledFirst), submission(ownerB, b, cancelledSecond), () -> true, () -> {}, error -> fail("Cancelled comparison"));
		assertTrue(cancelled.cancel(false));
		cancelledFirst.complete(TerrainDensityResult.fromCompleteTerrain(a.identity(), data(0), 0)); cancelledSecond.complete(TerrainDensityResult.fromCompleteTerrain(b.identity(), data(1), 0)); assertTrue(cancelled.isCancelled());
	}
	@Test void localApprovalCannotCrossWireAndBothEpochsMustRemainCurrentForCacheOrApplication() {
		var a = job(); var b = job(); AtomicBoolean firstCurrent = new AtomicBoolean(true), secondCurrent = new AtomicBoolean(true);
		AtomicInteger applied = new AtomicInteger();
		var approved = CompleteTerrainPeerVerifier.agree(submission(UUID.randomUUID(), a, CompletableFuture.completedFuture(TerrainDensityResult.fromCompleteTerrain(a.identity(), data(0), 0))),
			submission(UUID.randomUUID(), b, CompletableFuture.completedFuture(TerrainDensityResult.fromCompleteTerrain(b.identity(), data(0), 0))),
			() -> firstCurrent.get() && secondCurrent.get(), applied::incrementAndGet, error -> fail(error)).join();
		assertTrue(approved.hasPeerVerification()); approved.requireCurrentAuthority();
		var decoded = TerrainDensityResultEnvelope.encode(approved, TerrainWorkKind.COMPLETE_TERRAIN).decode();
		assertEquals(approved, decoded); assertFalse(decoded.hasPeerVerification());
		decoded.recordPeerApplication(); assertEquals(0, applied.get());
		approved.recordPeerApplication(); approved.recordPeerApplication(); assertEquals(1, applied.get());
		var cache = new RemoteDensityResultCache(2);
		var key = new RemoteDensityResultCache.Key(0, a.identity().dimension(), 1, -2, a.identity().contextFingerprint(), a.noiseSettings(), -64, 16, 1, 1, UUID.randomUUID(), 0, a.workKind());
		for (AtomicBoolean current : new AtomicBoolean[]{firstCurrent, secondCurrent}) {
			cache.put(key, approved); assertTrue(cache.available(key));
			current.set(false); assertFalse(cache.available(key)); assertTrue(cache.takeResult(key).isEmpty());
			assertThrows(IllegalArgumentException.class, approved::requireCurrentAuthority); current.set(true);
		}
		cache.put(key, approved); secondCurrent.set(false); cache.removeStaleAuthority(); assertEquals(0, cache.size());
		assertThrows(IllegalArgumentException.class, () -> new RemoteDensityField(a, approved));
		var stale = CompleteTerrainPeerVerifier.agree(submission(UUID.randomUUID(), a, CompletableFuture.completedFuture(TerrainDensityResult.fromCompleteTerrain(a.identity(), data(0), 0))),
			submission(UUID.randomUUID(), b, CompletableFuture.completedFuture(TerrainDensityResult.fromCompleteTerrain(b.identity(), data(0), 0))), () -> false, () -> fail("Stale application"), error -> fail(error));
		assertTrue(stale.isCompletedExceptionally());
	}
	@Test void peerDrawRetainsInitialAuditsAndRacedAdmissionForcesFreshFullAuditToken() {
		AtomicInteger boundSeen = new AtomicInteger();
		var policy = new CompleteTerrainAuditPolicy<String>(4, (int bound) -> {boundSeen.set(bound); return bound - 1;});
		assertFalse(policy.trusted("a"));
		for (int i = 0; i < 2; i++) { var token = policy.select("a", UUID.randomUUID(), 64); assertTrue(token.requiresFullAudit()); assertTrue(policy.accept(token)); }
		assertTrue(policy.trusted("a")); assertEquals(0, boundSeen.get());
		var peer = policy.select("a", UUID.randomUUID(), 64); assertFalse(peer.requiresFullAudit()); assertEquals(64, boundSeen.get());
		var replacement = policy.requireFullAudit(peer); assertTrue(replacement.requiresFullAudit()); assertFalse(policy.accept(peer)); policy.cancel(peer); assertTrue(policy.current(replacement)); assertTrue(policy.accept(replacement));
		var normal = policy.select("a", UUID.randomUUID()); assertEquals(8, boundSeen.get()); policy.cancel(normal);
		assertThrows(IllegalArgumentException.class, () -> policy.select("a", UUID.randomUUID(), 0));
		policy.invalidate("a"); assertFalse(policy.trusted("a")); assertThrows(IllegalStateException.class, () -> policy.requireFullAudit(peer));
		String[] names = {"worldgen_assist.remote.work_kind", "worldgen_assist.remote.allow_complete_terrain", "worldgen_assist.remote.complete_verification"};
		String[] saved = java.util.Arrays.stream(names).map(System::getProperty).toArray(String[]::new);
		try {
			System.setProperty(names[0], "complete"); System.setProperty(names[1], "true"); System.setProperty(names[2], "server"); assertFalse(CompleteTerrainVerificationMode.peerRequested());
			System.setProperty(names[2], "peer"); assertTrue(CompleteTerrainVerificationMode.peerRequested());
			System.setProperty(names[1], "false"); assertFalse(CompleteTerrainVerificationMode.peerRequested());
		} finally { for (int i = 0; i < names.length; i++) { if (saved[i] == null) System.clearProperty(names[i]); else System.setProperty(names[i], saved[i]); } }
	}
}
