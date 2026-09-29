package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import io.github.genichimaruo.worldgenassist.common.*;
import io.github.genichimaruo.worldgenassist.network.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RemoteBatchDispatch263Test {
	private static final Identifier DIMENSION = Identifier.parse("minecraft:overworld");
	private static final WorldgenContextFingerprint FINGERPRINT = WorldgenContextFingerprint.fromHex("12".repeat(32));
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void batchesByOwnerWithinExistingLimitsAndAcceptsIndependentReplies() {
		var sender = new Sender(); var coordinator = coordinator(sender);
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(); hello(coordinator, a); hello(coordinator, b);
		var submissions = new ArrayList<RemoteJobCoordinator.Submission>();
		coordinator.withJobBatch(() -> {
			for (int x = 0; x < 4; x++) submissions.add(submit(coordinator, a, x));
			assertTrue(coordinator.trySubmitForOwner(a, DIMENSION, 5, 0, FINGERPRINT, RemoteBatchDispatch263Test::job).isEmpty());
			for (int x = 0; x < 4; x++) submissions.add(submit(coordinator, b, x));
			assertTrue(sender.groups.isEmpty());
		});
		assertEquals(List.of(a, b), sender.owners); assertEquals(List.of(4, 4), sender.groups.stream().map(List::size).toList());
		var first = submissions.getFirst();
		var result = new TerrainDensityResult(first.job().identity(), new double[first.job().sampleCount()], 1);
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.OWNER_MISMATCH, coordinator.handleResult(b, result));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, coordinator.handleResult(a, result));
		assertSame(result, first.result().join()); assertFalse(submissions.get(1).result().isDone());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.DUPLICATE, coordinator.handleResult(a, result));
		coordinator.shutdown(); assertEquals(0, coordinator.pendingCount());
	}
	@Test void cancelledUnsentJobsAreOmittedAndFailedBatchReleasesReservations() {
		var sender = new Sender(); sender.fail = true; var coordinator = coordinator(sender);
		UUID owner = UUID.randomUUID(); hello(coordinator, owner);
		var submissions = new ArrayList<RemoteJobCoordinator.Submission>();
		coordinator.withJobBatch(() -> {
			for (int x = 0; x < 3; x++) submissions.add(submit(coordinator, owner, x));
			coordinator.cancelJob(owner, submissions.getFirst().job().identity());
		});
		assertEquals(2, sender.groups.getFirst().size());
		assertTrue(submissions.stream().allMatch(s -> s.result().isCompletedExceptionally()));
		assertEquals(0, coordinator.pendingCount());
		sender.fail = false; assertNotNull(submit(coordinator, owner, 9)); coordinator.shutdown();
	}
	@Test void batchCodecRejectsOversizeAndDuplicateJobsAndRoundTripsExactly() {
		var identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), DIMENSION, 0, 0, FINGERPRINT);
		var job = job(identity); var batch = new TerrainJobBatchPayload(List.of(job));
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			TerrainJobBatchPayload.CODEC.encode(buffer, batch);
			assertEquals(batch, TerrainJobBatchPayload.CODEC.decode(buffer));
			buffer.clear(); buffer.writeVarInt(TerrainJobBatchPayload.MAX_JOBS + 1);
			assertThrows(IllegalArgumentException.class, () -> TerrainJobBatchPayload.CODEC.decode(buffer));
		} finally { buffer.release(); }
		assertThrows(IllegalArgumentException.class, () -> new TerrainJobBatchPayload(List.of()));
		assertThrows(IllegalArgumentException.class, () -> new TerrainJobBatchPayload(List.of(job, job)));
	}
	private static RemoteJobCoordinator coordinator(Sender sender) {
		return new RemoteJobCoordinator(new RemoteWorldgenConfig(true, RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW,
			8, Duration.ofSeconds(30), 128, false, 20, 8, 8), sender);
	}
	private static void hello(RemoteJobCoordinator coordinator, UUID owner) {
		assertTrue(coordinator.handleHello(owner, new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, 4, "test")).accepted());
		for (int i = 0; i < 6; i++) coordinator.recordValidated(owner);
	}
	private static RemoteJobCoordinator.Submission submit(RemoteJobCoordinator coordinator, UUID owner, int x) {
		return coordinator.trySubmitForOwner(owner, DIMENSION, x, 0, FINGERPRINT, RemoteBatchDispatch263Test::job).orElseThrow();
	}
	private static TerrainDensityJob job(TerrainJobIdentity identity) {
		return new TerrainDensityJob(identity, 8675309L, true, DIMENSION, 0, 16, 4, 8);
	}
	private static final class Sender implements RemoteJobSender {
		final List<UUID> owners = new ArrayList<>(); final List<List<TerrainDensityJob>> groups = new ArrayList<>(); boolean fail;
		@Override public boolean canSend(UUID owner) { return true; }
		@Override public void sendJob(UUID owner, TerrainJobRequestPayload payload) { sendJobs(owner, List.of(payload.job())); }
		@Override public void sendJobs(UUID owner, List<TerrainDensityJob> jobs) {
			owners.add(owner); groups.add(List.copyOf(jobs)); if (fail) throw new IllegalStateException("test send failure");
		}
		@Override public void sendCancel(UUID owner, TerrainJobCancelPayload payload) {}
	}
}
