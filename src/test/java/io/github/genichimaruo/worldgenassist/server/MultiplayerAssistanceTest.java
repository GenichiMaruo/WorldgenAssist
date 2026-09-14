package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.genichimaruo.worldgenassist.client.SeededLeafClientTestBridge;
import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Ownership boundaries for the opt-in, player-owned concurrent-assistance route. */
class MultiplayerAssistanceTest {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
	private static final Identifier NETHER = Identifier.parse("minecraft:the_nether");
	private static final UUID OWNER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OWNER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID OWNER_C = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final WorldgenContextFingerprint FINGERPRINT = WorldgenContextFingerprint.fromHex("12".repeat(32));

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@Timeout(30)
	void twoOwnersCompleteRealIndependentExchangesAndWrongOwnerCannotClaim() throws Exception {
		Fixture fixture = fixture(2, 1);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(
			fixture.authority, 32, Duration.ofSeconds(10), 2
		); SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator, 2)) {
			SeededLeafJobOrchestrator.Connection ownerA = orchestrator.connect(OWNER_A);
			SeededLeafJobOrchestrator.Connection ownerB = orchestrator.connect(OWNER_B);
			SeededLeafJobOrchestrator.Attempt attemptA = orchestrator.submit(ownerA, fixture.request(10, -20), queue);
			SeededLeafJobOrchestrator.Attempt attemptB = orchestrator.submit(ownerB, fixture.request(11, -20), queue);
			await(() -> queue.pendingCount() == 2, "both owner exchanges to queue");
			assertEquals(2, orchestrator.pendingCount());
			assertTrue(orchestrator.isBusy(ownerA));
			assertTrue(orchestrator.isBusy(ownerB));

			List<Dispatch> dispatches = drain(queue);
			assertEquals(2, dispatches.size());
			Dispatch first = dispatches.stream().filter(dispatch -> dispatch.owner == ownerA).findFirst().orElseThrow();
			Dispatch second = dispatches.stream().filter(dispatch -> dispatch.owner == ownerB).findFirst().orElseThrow();
			SeededLeafDensityResultEnvelope firstResult = result(fixture, first.authorization);
			assertFalse(queue.receive(ownerB, firstResult), "another owner must not consume this claim");
			assertTrue(queue.receive(ownerA, firstResult));
			assertTrue(queue.receive(ownerB, result(fixture, second.authorization)));

			assertEquals(SeededLeafJobOrchestrator.Status.READY, completion(attemptA).status());
			assertEquals(SeededLeafJobOrchestrator.Status.READY, completion(attemptB).status());
			assertEquals(0, fixture.authority.pendingCount());
			long chargedForBothOwners = fixture.authority.globalDisclosureSnapshot().usedEntries();
			assertTrue(chargedForBothOwners > 0L);
			orchestrator.reload();
			assertEquals(chargedForBothOwners, fixture.authority.globalDisclosureSnapshot().usedEntries(),
				"one authority retains one shared, non-refundable budget across owners");
		}
	}

	@Test
	@Timeout(30)
	void disconnectAndQuarantineAreOwnerScopedAndReconnectRestoresOnlyThatOwner() throws Exception {
		Fixture fixture = fixture(2, 2);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(
			fixture.authority, 32, Duration.ofSeconds(10), 2
		); SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator, 2)) {
			SeededLeafJobOrchestrator.Connection ownerA = orchestrator.connect(OWNER_A);
			SeededLeafJobOrchestrator.Connection ownerB = orchestrator.connect(OWNER_B);
			SeededLeafJobOrchestrator.Attempt disconnected = orchestrator.submit(ownerA, fixture.request(10, -20), queue);
			SeededLeafJobOrchestrator.Attempt healthy = orchestrator.submit(ownerB, fixture.request(11, -20), queue);
			await(() -> queue.pendingCount() == 2, "both owner exchanges before disconnect");
			SeededLeafJobOrchestrator.Connection replacementA = orchestrator.connect(OWNER_A);
			assertEquals(SeededLeafJobOrchestrator.Status.DISCONNECTED, completion(disconnected).status());
			orchestrator.disconnect(ownerA);
			assertEquals(1, queue.pendingCount(), "replacing one UUID must retain another owner's transfer");
			Dispatch ownerBDispatch = only(drain(queue));
			assertEquals(ownerB, ownerBDispatch.owner);
			assertTrue(queue.receive(ownerB, result(fixture, ownerBDispatch.authorization)));
			assertEquals(SeededLeafJobOrchestrator.Status.READY, completion(healthy).status());

			assertEquals(SeededLeafJobOrchestrator.Status.READY,
				completion(orchestrator.submit(replacementA, fixture.request(12, -20), responding(fixture))).status());

			SeededLeafJobOrchestrator.Attempt rejected = orchestrator.submit(ownerB, fixture.request(13, -20), queue);
			await(() -> queue.pendingCount() == 1, "owner B exchange before invalid reply");
			Dispatch invalid = only(drain(queue));
			assertTrue(queue.receive(ownerB, corrupt(fixture, invalid.authorization)));
			assertEquals(SeededLeafJobOrchestrator.Status.RESULT_REJECTED, completion(rejected).status());
			assertEquals(SeededLeafJobOrchestrator.Status.WORKER_QUARANTINED,
				completion(orchestrator.submit(ownerB, fixture.request(14, -20), responding(fixture))).status());
			assertEquals(SeededLeafJobOrchestrator.Status.READY,
				completion(orchestrator.submit(replacementA, fixture.request(15, -20), responding(fixture))).status());

			orchestrator.disconnect(ownerB);
			SeededLeafJobOrchestrator.Connection reconnectedB = orchestrator.connect(OWNER_B);
			assertEquals(SeededLeafJobOrchestrator.Status.READY,
				completion(orchestrator.submit(reconnectedB, fixture.request(16, -20), responding(fixture))).status());
		}
	}

	@Test
	@Timeout(20)
	void globalAndPerOwnerCapacityRemainOccupiedUntilRecordingActuallyExitsAndLifecycleCancelsAll() throws Exception {
		Fixture fixture = fixture(2, 1);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(
			fixture.authority, 32, Duration.ofSeconds(10), request -> {
				entered.countDown();
				awaitUninterruptibly(release, "recording release");
			}, System::nanoTime, 2
		); SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator, 2)) {
			SeededLeafJobOrchestrator.Connection ownerA = orchestrator.connect(OWNER_A);
			SeededLeafJobOrchestrator.Connection ownerB = orchestrator.connect(OWNER_B);
			SeededLeafJobOrchestrator.Connection ownerC = orchestrator.connect(OWNER_C);
			SeededLeafJobOrchestrator.Attempt attemptA = orchestrator.submit(ownerA, fixture.request(10, -20), queue);
			await(entered, "first recording entry");
			assertEquals(SeededLeafJobOrchestrator.Status.BUSY,
				completion(orchestrator.submit(ownerA, fixture.request(12, -20), queue)).status(),
				"one owner cannot occupy a second attempt");
			SeededLeafJobOrchestrator.Attempt attemptB = orchestrator.submit(ownerB, fixture.request(11, -20), queue);
			assertEquals(2, orchestrator.pendingCount());
			assertEquals(SeededLeafJobOrchestrator.Status.BUSY,
				completion(orchestrator.submit(ownerC, fixture.request(13, -20), queue)).status(),
				"a third owner cannot exceed the global attempt capacity");
			assertTrue(orchestrator.suspendAdmission());
			assertEquals(SeededLeafJobOrchestrator.Status.CANCELLED, completion(attemptA).status());
			assertEquals(SeededLeafJobOrchestrator.Status.CANCELLED, completion(attemptB).status());
			assertTrue(orchestrator.isBusy(), "logical cancellation cannot release an interrupt-insensitive recording");
			assertTrue(orchestrator.isBusy(ownerA));
			orchestrator.resumeAdmission();
			release.countDown();
			await(() -> !orchestrator.isBusy(), "actual recorder exit");
			assertEquals(0, orchestrator.pendingCount());
		} finally {
			release.countDown();
		}
	}

	@Test
	@Timeout(20)
	void reloadCancelsBothOwnersOnceWithoutRefundingTheSharedDisclosureBudget() throws Exception {
		Fixture fixture = fixture(2, 1);
		try (SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(
			fixture.authority, 32, Duration.ofSeconds(10), 2
		); SeededLeafDispatchQueue queue = new SeededLeafDispatchQueue(orchestrator, 2)) {
			SeededLeafJobOrchestrator.Connection ownerA = orchestrator.connect(OWNER_A);
			SeededLeafJobOrchestrator.Connection ownerB = orchestrator.connect(OWNER_B);
			SeededLeafJobOrchestrator.Attempt attemptA = orchestrator.submit(ownerA, fixture.request(10, -20), queue);
			SeededLeafJobOrchestrator.Attempt attemptB = orchestrator.submit(ownerB, fixture.request(11, -20), queue);
			await(() -> queue.pendingCount() == 2, "both owner exchanges before reload");
			long charged = fixture.authority.globalDisclosureSnapshot().usedEntries();
			assertTrue(charged > 0L);
			orchestrator.reload();
			assertEquals(SeededLeafJobOrchestrator.Status.STALE_CONTEXT, completion(attemptA).status());
			assertEquals(SeededLeafJobOrchestrator.Status.STALE_CONTEXT, completion(attemptB).status());
			assertEquals(0, orchestrator.pendingCount());
			assertEquals(0, queue.pendingCount());
			assertEquals(charged, fixture.authority.globalDisclosureSnapshot().usedEntries());
		}
	}

	@Test
	void workerRegistryAndCoordinatorUseExactOwnerWithMultipleWorkersButCompatibilitySelectionStaysSoleOnly() {
		FakeSender sender = new FakeSender();
		WorkerRegistry workers = new WorkerRegistry(1);
		AtomicLong clock = new AtomicLong();
		RemoteJobCoordinator coordinator = new RemoteJobCoordinator(
			true,
			workers,
			new PendingTerrainJobRegistry(2, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), clock::get, UUID::randomUUID),
			sender
		);
		coordinator.handleHello(OWNER_A, hello());
		coordinator.handleHello(OWNER_B, hello());
		assertTrue(coordinator.trySubmit(OVERWORLD, 0, 0, FINGERPRINT, MultiplayerAssistanceTest::job).isEmpty());
		RemoteJobCoordinator.Submission first = coordinator.trySubmitForOwner(
			OWNER_A, OVERWORLD, 0, 0, FINGERPRINT, MultiplayerAssistanceTest::job
		).orElseThrow();
		RemoteJobCoordinator.Submission second = coordinator.trySubmitForOwner(
			OWNER_B, OVERWORLD, 1, 0, FINGERPRINT, MultiplayerAssistanceTest::job
		).orElseThrow();
		assertEquals(OWNER_A, first.ownerId());
		assertEquals(OWNER_B, second.ownerId());
		assertTrue(coordinator.trySubmitForOwner(OWNER_A, OVERWORLD, 2, 0, FINGERPRINT, MultiplayerAssistanceTest::job).isEmpty());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.OWNER_MISMATCH,
			coordinator.handleResult(OWNER_B, zeroResult(first.job())));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			coordinator.handleResult(OWNER_A, zeroResult(first.job())));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			coordinator.handleResult(OWNER_B, zeroResult(second.job())));
		assertEquals(0, coordinator.pendingCount());
	}

	@Test
	void ownerDemandUsesVanillaViewShapeDimensionAndDeterministicOverlapWhileCacheSeparatesOwners() {
		PlayerChunkDemand nearA = new PlayerChunkDemand(OWNER_A, OVERWORLD, 0, 0, 2);
		PlayerChunkDemand nearB = new PlayerChunkDemand(OWNER_B, OVERWORLD, 4, 0, 2);
		PlayerChunkDemand nether = new PlayerChunkDemand(OWNER_C, NETHER, 0, 0, 2);
		assertTrue(nearA.includes(OVERWORLD, 2, 0));
		assertFalse(nearA.includes(OVERWORLD, 3, 3));
		assertFalse(nearA.includes(NETHER, 0, 0));
		assertEquals(OWNER_A, PlayerChunkDemand.select(List.of(nearA, nearB, nether), OVERWORLD, 2, 0).orElseThrow().ownerId(),
			"equal distance overlap must break ties by UUID");
		assertEquals(OWNER_B, PlayerChunkDemand.select(List.of(nearA, nearB), OVERWORLD, 4, 0).orElseThrow().ownerId());
		assertTrue(PlayerChunkDemand.select(List.of(nearA, nearB), NETHER, 2, 0).isEmpty());

		RemoteDensityResultCache.Key historical = cacheKey(null);
		RemoteDensityResultCache.Key ownerAKey = cacheKey(OWNER_A);
		RemoteDensityResultCache.Key ownerBKey = cacheKey(OWNER_B);
		RemoteDensityResultCache.Key reconnectAKey = cacheKey(OWNER_A, 2L);
		assertEquals(null, historical.ownerId());
		assertFalse(ownerAKey.equals(ownerBKey));
		assertFalse(ownerAKey.equals(reconnectAKey), "a replacement owner connection has a distinct cache identity");
		RemoteDensityResultCache cache = new RemoteDensityResultCache(2);
		cache.put(ownerAKey, cacheResult(ownerAKey, 1.0));
		cache.put(ownerBKey, cacheResult(ownerBKey, 2.0));
		assertTrue(cache.get(ownerAKey).isPresent());
		assertTrue(cache.get(reconnectAKey).isEmpty(), "a prior owner connection must not satisfy its replacement");
		cache.removeOwner(OWNER_A);
		assertTrue(cache.get(ownerAKey).isEmpty());
		assertTrue(cache.get(ownerBKey).isPresent(), "owner eviction must retain other owners' cached predictions");
	}

	private static List<Dispatch> drain(SeededLeafDispatchQueue queue) {
		List<Dispatch> dispatched = new ArrayList<>();
		for (int tries = 0; tries < 4 && dispatched.size() < queue.pendingCount(); tries++) {
			queue.drain((owner, authorization) -> dispatched.add(new Dispatch(owner, authorization)), (owner, claim) -> { });
		}
		return dispatched;
	}

	private static Dispatch only(List<Dispatch> dispatches) {
		assertEquals(1, dispatches.size());
		return dispatches.getFirst();
	}

	private static SeededLeafJobOrchestrator.ClientExchange responding(Fixture fixture) {
		return new SeededLeafJobOrchestrator.ClientExchange() {
			@Override public java.util.concurrent.CompletionStage<SeededLeafDensityResultEnvelope> send(
				SeededLeafJobOrchestrator.Connection owner, AuthorizedSeededLeafJob authorization
			) {
				return java.util.concurrent.CompletableFuture.completedFuture(result(fixture, authorization));
			}
			@Override public void cancel(SeededLeafJobOrchestrator.Connection owner, SeededLeafJobClaim claim) { }
		};
	}

	private static SeededLeafDensityResultEnvelope result(Fixture fixture, AuthorizedSeededLeafJob authorization) {
		return SeededLeafDensityResultEnvelope.encode(SeededLeafClientTestBridge.compute(fixture.registries, OVERWORLD, authorization));
	}

	private static SeededLeafDensityResultEnvelope corrupt(Fixture fixture, AuthorizedSeededLeafJob authorization) {
		SeededLeafDensityResult actual = SeededLeafClientTestBridge.compute(fixture.registries, OVERWORLD, authorization);
		double[] densities = actual.densities();
		densities[0] = Math.nextUp(densities[0]);
		return SeededLeafDensityResultEnvelope.encode(new SeededLeafDensityResult(actual.claim(), densities, actual.clientComputeNanos()));
	}

	private static SeededLeafJobOrchestrator.Result completion(SeededLeafJobOrchestrator.Attempt attempt) throws Exception {
		return attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
	}

	private static Fixture fixture(int maxTotal, int maxPerOwner) {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		RandomState randomState = RandomState.create(settings.value(), noises, 8675309L);
		AtomicInteger sequence = new AtomicInteger();
		byte[] key = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		Arrays.fill(key, (byte) 0x4c);
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
			maxTotal, maxPerOwner, 100_000, 100_000, Duration.ofSeconds(10), Duration.ofSeconds(10), System::nanoTime,
			() -> new UUID(0L, sequence.incrementAndGet()),
			() -> OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			() -> SeededLeafJobAuthenticator.fromKey(key), new SeededLeafVolatileDisclosureBudget(100_000)
		);
		authority.start();
		return new Fixture(registries, authority, randomState, settings);
	}

	private static TerrainDensityJob job(TerrainJobIdentity identity) {
		return new TerrainDensityJob(identity, 8675309L, true, OVERWORLD, -64, 8, 4, 8);
	}

	private static TerrainDensityResult zeroResult(TerrainDensityJob job) {
		return new TerrainDensityResult(job.identity(), new double[job.sampleCount()], 0L);
	}

	private static WorkerHelloPayload hello() {
		return new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 1, "multiplayer-test");
	}

	private static RemoteDensityResultCache.Key cacheKey(UUID ownerId) {
		return new RemoteDensityResultCache.Key(0L, OVERWORLD, 0, 0, FINGERPRINT, OVERWORLD, -64, 8, 4, 8, ownerId);
	}

	private static RemoteDensityResultCache.Key cacheKey(UUID ownerId, long ownerGeneration) {
		return new RemoteDensityResultCache.Key(
			0L, OVERWORLD, 0, 0, FINGERPRINT, OVERWORLD, -64, 8, 4, 8, ownerId, ownerGeneration
		);
	}

	private static TerrainDensityResult cacheResult(RemoteDensityResultCache.Key key, double density) {
		double[] densities = new double[key.sampleCount()];
		Arrays.fill(densities, density);
		return new TerrainDensityResult(new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), key.dimension(), key.chunkX(), key.chunkZ(), key.contextFingerprint()
		), densities, 0L);
	}

	private static void await(java.util.function.BooleanSupplier condition, String description) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertTrue(condition.getAsBoolean(), () -> "Timed out waiting for " + description);
	}

	private static void await(CountDownLatch latch, String description) {
		try {
			assertTrue(latch.await(2, TimeUnit.SECONDS), () -> "Timed out waiting for " + description);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for " + description, interrupted);
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch, String description) {
		boolean interrupted = false;
		try {
			while (latch.getCount() != 0L) {
				try {
					await(latch, description);
				} catch (AssertionError failure) {
					if (!Thread.interrupted()) { throw failure; }
					interrupted = true;
				}
			}
		} finally {
			if (interrupted) { Thread.currentThread().interrupt(); }
		}
	}

	private record Dispatch(SeededLeafJobOrchestrator.Connection owner, AuthorizedSeededLeafJob authorization) { }

	private record Fixture(
		HolderLookup.Provider registries,
		SeededLeafJobAuthority authority,
		RandomState randomState,
		Holder.Reference<NoiseGeneratorSettings> settings
	) {
		private SeededLeafJobOrchestrator.RecordingRequest request(int chunkX, int chunkZ) {
			return new SeededLeafJobOrchestrator.RecordingRequest(
				OVERWORLD, chunkX, chunkZ, randomState, settings, NoiseSettings.create(-64, 16, 1, 2), 100_000
			);
		}
	}

	private static final class FakeSender implements RemoteJobSender {
		private final List<TerrainJobRequestPayload> jobs = new ArrayList<>();
		private final List<TerrainJobCancelPayload> cancels = new ArrayList<>();
		@Override public boolean canSend(UUID ownerId) { return true; }
		@Override public void sendJob(UUID ownerId, TerrainJobRequestPayload payload) { jobs.add(payload); }
		@Override public void sendCancel(UUID ownerId, TerrainJobCancelPayload payload) { cancels.add(payload); }
	}
}
