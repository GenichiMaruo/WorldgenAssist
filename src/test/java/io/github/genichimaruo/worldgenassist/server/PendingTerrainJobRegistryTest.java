package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PendingTerrainJobRegistryTest {
	private static final UUID OWNER_A = UUID.fromString("16539a37-f40d-4b02-a378-8dc17c7b22db");
	private static final UUID OWNER_B = UUID.fromString("4f4b52e7-c70d-42aa-93de-a14fc1145153");
	private static final UUID OWNER_C = UUID.fromString("fa8d2b6f-20f3-4361-ae56-81cb3b9a4d4f");
	private static final Identifier DIMENSION = Identifier.parse("minecraft:overworld");
	private static final WorldgenContextFingerprint CONTEXT = WorldgenContextFingerprint.fromBytes(
		new byte[WorldgenContextFingerprint.BYTE_LENGTH]
	);

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void enforcesGlobalAndPerOwnerInFlightLimits() {
		MutableNanoClock clock = new MutableNanoClock();
		PendingTerrainJobRegistry registry = registry(2, 1, clock, new SequentialJobIds());

		assertEquals(PendingTerrainJobRegistry.RegistrationStatus.ACCEPTED, register(registry, OWNER_A, 0).status());
		assertEquals(
			PendingTerrainJobRegistry.RegistrationStatus.OWNER_LIMIT_REACHED,
			register(registry, OWNER_A, 1).status()
		);
		assertEquals(PendingTerrainJobRegistry.RegistrationStatus.ACCEPTED, register(registry, OWNER_B, 2).status());
		assertEquals(
			PendingTerrainJobRegistry.RegistrationStatus.GLOBAL_LIMIT_REACHED,
			register(registry, OWNER_C, 3).status()
		);
		assertEquals(2, registry.pendingCount());
		assertEquals(1, registry.pendingCountFor(OWNER_A));
		assertEquals(1, registry.pendingCountFor(OWNER_B));
	}

	@Test
	void acceptsAnExactResponseOnceAndRejectsItsReplay() {
		PendingTerrainJobRegistry registry = registry(2, 2, new MutableNanoClock(), new SequentialJobIds());
		TerrainJobIdentity identity = accepted(register(registry, OWNER_A, 0));

		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, registry.evaluateResponse(OWNER_A, identity));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.DUPLICATE, registry.evaluateResponse(OWNER_A, identity));
		assertEquals(
			PendingTerrainJobRegistry.CancellationStatus.ALREADY_RESPONDED,
			registry.cancel(OWNER_A, identity.jobId())
		);
		assertEquals(0, registry.pendingCount());
		assertEquals(1, registry.trackedCount());
	}

	@Test
	void inspectionValidatesWithoutConsumingTheResponse() {
		PendingTerrainJobRegistry registry = registry(2, 2, new MutableNanoClock(), new SequentialJobIds());
		TerrainJobIdentity identity = accepted(register(registry, OWNER_A, 0));

		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, registry.inspectResponse(OWNER_A, identity));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, registry.inspectResponse(OWNER_A, identity));
		assertEquals(1, registry.pendingCount());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, registry.evaluateResponse(OWNER_A, identity));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.DUPLICATE, registry.inspectResponse(OWNER_A, identity));
	}

	@Test
	void rejectsOwnerAndIdentityMismatchWithoutConsumingTheJob() {
		PendingTerrainJobRegistry registry = registry(2, 2, new MutableNanoClock(), new SequentialJobIds());
		TerrainJobIdentity identity = accepted(register(registry, OWNER_A, 0));
		TerrainJobIdentity wrongChunk = new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			identity.jobId(),
			identity.dimension(),
			1,
			0,
			identity.contextFingerprint()
		);

		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.OWNER_MISMATCH,
			registry.evaluateResponse(OWNER_B, identity)
		);
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.IDENTITY_MISMATCH,
			registry.evaluateResponse(OWNER_A, wrongChunk)
		);
		assertEquals(1, registry.pendingCount());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, registry.evaluateResponse(OWNER_A, identity));
	}

	@Test
	void expiresOnlyAtTheMonotonicDeadline() {
		MutableNanoClock clock = new MutableNanoClock();
		PendingTerrainJobRegistry registry = registry(2, 2, clock, new SequentialJobIds());
		TerrainJobIdentity identity = accepted(register(registry, OWNER_A, 0));

		clock.advance(Duration.ofNanos(99));
		assertTrue(registry.expireTimedOut().isEmpty());
		clock.advance(Duration.ofNanos(1));
		assertEquals(List.of(identity), registry.expireTimedOut());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.EXPIRED, registry.evaluateResponse(OWNER_A, identity));
		assertEquals(PendingTerrainJobRegistry.CancellationStatus.EXPIRED, registry.cancel(OWNER_A, identity.jobId()));
		assertEquals(0, registry.pendingCount());
	}

	@Test
	void cancellationIsIdempotentAndBlocksLaterResponses() {
		PendingTerrainJobRegistry registry = registry(2, 2, new MutableNanoClock(), new SequentialJobIds());
		TerrainJobIdentity identity = accepted(register(registry, OWNER_A, 0));

		assertEquals(PendingTerrainJobRegistry.CancellationStatus.CANCELLED, registry.cancel(OWNER_A, identity.jobId()));
		assertEquals(
			PendingTerrainJobRegistry.CancellationStatus.ALREADY_CANCELLED,
			registry.cancel(OWNER_A, identity.jobId())
		);
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.CANCELLED, registry.evaluateResponse(OWNER_A, identity));
		assertEquals(0, registry.pendingCount());
	}

	@Test
	void disconnectCancellationAffectsOnlyThatOwnerAndReturnsFallbackWork() {
		PendingTerrainJobRegistry registry = registry(4, 3, new MutableNanoClock(), new SequentialJobIds());
		TerrainJobIdentity first = accepted(register(registry, OWNER_A, 0));
		TerrainJobIdentity second = accepted(register(registry, OWNER_A, 1));
		TerrainJobIdentity otherOwner = accepted(register(registry, OWNER_B, 2));

		List<TerrainJobIdentity> cancelled = registry.cancelAllForOwner(OWNER_A);

		assertEquals(List.of(first, second), cancelled);
		assertThrows(UnsupportedOperationException.class, () -> cancelled.add(otherOwner));
		assertEquals(1, registry.pendingCount());
		assertEquals(0, registry.pendingCountFor(OWNER_A));
		assertEquals(1, registry.pendingCountFor(OWNER_B));
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.CANCELLED,
			registry.evaluateResponse(OWNER_A, first)
		);
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			registry.evaluateResponse(OWNER_B, otherOwner)
		);
	}

	@Test
	void serverShutdownCancellationReturnsEveryRemainingPendingJob() {
		PendingTerrainJobRegistry registry = registry(4, 3, new MutableNanoClock(), new SequentialJobIds());
		TerrainJobIdentity alreadyResponded = accepted(register(registry, OWNER_A, 0));
		TerrainJobIdentity firstPending = accepted(register(registry, OWNER_A, 1));
		TerrainJobIdentity secondPending = accepted(register(registry, OWNER_B, 2));
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.ACCEPTED,
			registry.evaluateResponse(OWNER_A, alreadyResponded)
		);

		assertEquals(List.of(firstPending, secondPending), registry.cancelAll());
		assertTrue(registry.cancelAll().isEmpty());
		assertEquals(0, registry.pendingCount());
		assertEquals(
			PendingTerrainJobRegistry.ResponseStatus.CANCELLED,
			registry.evaluateResponse(OWNER_B, secondPending)
		);
	}

	@Test
	void terminalEntriesAreBoundedAndEvictedForNewWork() {
		PendingTerrainJobRegistry registry = registry(1, 1, new MutableNanoClock(), new SequentialJobIds());
		TerrainJobIdentity first = accepted(register(registry, OWNER_A, 0));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, registry.evaluateResponse(OWNER_A, first));

		TerrainJobIdentity second = accepted(register(registry, OWNER_B, 1));

		assertEquals(1, registry.trackedCount());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.UNKNOWN_JOB, registry.evaluateResponse(OWNER_A, first));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, registry.evaluateResponse(OWNER_B, second));
	}

	@Test
	void terminalEntriesExpireAfterTheConfiguredRetention() {
		MutableNanoClock clock = new MutableNanoClock();
		PendingTerrainJobRegistry registry = registry(2, 2, clock, new SequentialJobIds());
		TerrainJobIdentity identity = accepted(register(registry, OWNER_A, 0));
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.ACCEPTED, registry.evaluateResponse(OWNER_A, identity));

		clock.advance(Duration.ofNanos(49));
		assertEquals(1, registry.trackedCount());
		clock.advance(Duration.ofNanos(1));
		assertEquals(0, registry.trackedCount());
		assertEquals(PendingTerrainJobRegistry.ResponseStatus.UNKNOWN_JOB, registry.evaluateResponse(OWNER_A, identity));
	}

	@Test
	void jobIdGenerationSkipsZeroAndTrackedCollisions() {
		UUID firstId = new UUID(1L, 1L);
		UUID secondId = new UUID(2L, 2L);
		Queue<UUID> ids = new ArrayDeque<>(Arrays.asList(new UUID(0L, 0L), firstId, firstId, secondId));
		PendingTerrainJobRegistry registry = registry(2, 2, new MutableNanoClock(), ids::remove);

		assertEquals(firstId, accepted(register(registry, OWNER_A, 0)).jobId());
		assertEquals(secondId, accepted(register(registry, OWNER_A, 1)).jobId());
	}

	@Test
	void concurrentResponsesHaveExactlyOneWinner() throws Exception {
		PendingTerrainJobRegistry registry = registry(2, 2, new MutableNanoClock(), new SequentialJobIds());
		TerrainJobIdentity identity = accepted(register(registry, OWNER_A, 0));
		List<Callable<PendingTerrainJobRegistry.ResponseStatus>> attempts = new ArrayList<>();
		for (int index = 0; index < 8; index++) {
			attempts.add(() -> registry.evaluateResponse(OWNER_A, identity));
		}

		List<PendingTerrainJobRegistry.ResponseStatus> statuses;
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			statuses = executor.invokeAll(attempts).stream().map(future -> {
				try {
					return future.get();
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
			}).toList();
		}

		assertEquals(1, statuses.stream().filter(status -> status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED).count());
		assertEquals(7, statuses.stream().filter(status -> status == PendingTerrainJobRegistry.ResponseStatus.DUPLICATE).count());
		assertEquals(0, registry.pendingCount());
	}

	@Test
	void validatesRegistryResourceBounds() {
		MutableNanoClock clock = new MutableNanoClock();
		SequentialJobIds ids = new SequentialJobIds();

		assertThrows(
			IllegalArgumentException.class,
			() -> new PendingTerrainJobRegistry(0, 1, Duration.ofNanos(1), Duration.ofNanos(1), clock, ids)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new PendingTerrainJobRegistry(1, 2, Duration.ofNanos(1), Duration.ofNanos(1), clock, ids)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new PendingTerrainJobRegistry(1, 1, Duration.ZERO, Duration.ofNanos(1), clock, ids)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new PendingTerrainJobRegistry(1, 1, Duration.ofNanos(1), Duration.ZERO, clock, ids)
		);
	}

	private static PendingTerrainJobRegistry registry(
		int maxTotal,
		int maxPerOwner,
		MutableNanoClock clock,
		Supplier<UUID> jobIds
	) {
		return new PendingTerrainJobRegistry(
			maxTotal,
			maxPerOwner,
			Duration.ofNanos(100),
			Duration.ofNanos(50),
			clock,
			jobIds
		);
	}

	private static PendingTerrainJobRegistry.RegistrationResult register(
		PendingTerrainJobRegistry registry,
		UUID ownerId,
		int chunkX
	) {
		return registry.tryRegister(ownerId, DIMENSION, chunkX, 0, CONTEXT);
	}

	private static TerrainJobIdentity accepted(PendingTerrainJobRegistry.RegistrationResult result) {
		assertEquals(PendingTerrainJobRegistry.RegistrationStatus.ACCEPTED, result.status());
		return result.identity().orElseThrow();
	}

	private static final class MutableNanoClock implements LongSupplier {
		private long nanos;

		@Override
		public long getAsLong() {
			return nanos;
		}

		private void advance(Duration duration) {
			nanos += duration.toNanos();
		}
	}

	private static final class SequentialJobIds implements Supplier<UUID> {
		private long next = 1L;

		@Override
		public UUID get() {
			return new UUID(0L, next++);
		}
	}
}
