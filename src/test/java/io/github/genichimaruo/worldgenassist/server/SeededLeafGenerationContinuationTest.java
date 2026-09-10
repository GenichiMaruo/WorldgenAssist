package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.client.SeededLeafClientTestBridge;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The inactive continuation must keep all vanilla work on its supplied generation executor. */
class SeededLeafGenerationContinuationTest {
	private static final UUID OWNER = UUID.fromString("cc2b4c1d-37a5-47e6-9d69-5648e93bd3b3");
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@Timeout(10)
	void fallbackCallsVanillaOnceOnTheGenerationExecutorAndNeverInstalls() throws Exception {
		try (Harness harness = new Harness()) {
			SeededLeafJobOrchestrator.Attempt attempt = harness.orchestrator.submit(null, harness.request(), harness.exchange());
			assertEquals(SeededLeafJobOrchestrator.Status.OWNER_INACTIVE, result(attempt).status());
			AtomicInteger calls = new AtomicInteger();
			AtomicReference<String> vanillaThread = new AtomicReference<>();
			CompletionStageResult<String> continuation = continuation(harness, attempt, () -> {
				calls.incrementAndGet();
				vanillaThread.set(Thread.currentThread().getName());
				return CompletableFuture.completedFuture("local");
			});
			assertEquals("local", continuation.future.get(2, TimeUnit.SECONDS));
			assertEquals(1, calls.get());
			assertEquals("SeededLeafGenerationTest", vanillaThread.get());
			assertEquals(0, harness.target.installs.get());
			assertEquals(0, harness.target.clears.get());
		}
	}

	@Test
	@Timeout(10)
	void continuationIsOneShotAndExecutorRejectionNeverRunsVanilla() throws Exception {
		try (Harness harness = new Harness()) {
			SeededLeafJobOrchestrator.Attempt attempt = harness.orchestrator.submit(null, harness.request(), harness.exchange());
			AtomicInteger calls = new AtomicInteger();
			CompletionStageResult<String> first = continuation(harness, attempt, () -> {
				calls.incrementAndGet();
				return CompletableFuture.completedFuture("first");
			});
			assertEquals("first", first.future.get(2, TimeUnit.SECONDS));
			CompletionStageResult<String> duplicate = continuation(harness, attempt, () -> CompletableFuture.completedFuture("second"));
			assertThrows(CompletionException.class, duplicate.future::join);
			assertEquals(1, calls.get());

			SeededLeafJobOrchestrator.Attempt rejected = harness.orchestrator.submit(null, harness.request(), harness.exchange());
			AtomicInteger rejectedCalls = new AtomicInteger();
			CompletableFuture<String> failed = SeededLeafGenerationContinuation.continueGeneration(
				harness.orchestrator, rejected, harness.target, command -> { throw new RejectedExecutionException("test rejection"); },
				() -> { rejectedCalls.incrementAndGet(); return CompletableFuture.completedFuture("never"); }
			).toCompletableFuture();
			assertThrows(CompletionException.class, failed::join);
			assertEquals(0, rejectedCalls.get());
		}
	}

	@Test
	@Timeout(10)
	void synchronousAndAsynchronousVanillaFailuresArePropagatedWithoutRetry() throws Exception {
		try (Harness harness = new Harness()) {
			SeededLeafJobOrchestrator.Attempt synchronous = harness.orchestrator.submit(null, harness.request(), harness.exchange());
			AtomicInteger synchronousCalls = new AtomicInteger();
			CompletableFuture<String> syncResult = SeededLeafGenerationContinuation.<String>continueGeneration(
				harness.orchestrator, synchronous, harness.target, harness.generation,
				() -> { synchronousCalls.incrementAndGet(); throw new IllegalStateException("sync vanilla failure"); }
			).toCompletableFuture();
			assertThrows(CompletionException.class, syncResult::join);
			assertEquals(1, synchronousCalls.get());

			SeededLeafJobOrchestrator.Attempt asynchronous = harness.orchestrator.submit(null, harness.request(), harness.exchange());
			AtomicInteger asynchronousCalls = new AtomicInteger();
			CompletableFuture<String> vanilla = new CompletableFuture<>();
			CompletableFuture<String> asyncResult = SeededLeafGenerationContinuation.continueGeneration(
				harness.orchestrator, asynchronous, harness.target, harness.generation,
				() -> { asynchronousCalls.incrementAndGet(); return vanilla; }
			).toCompletableFuture();
			vanilla.completeExceptionally(new IllegalArgumentException("async vanilla failure"));
			assertThrows(CompletionException.class, asyncResult::join);
			assertEquals(1, asynchronousCalls.get());
			assertEquals(0, harness.target.installs.get());
			assertEquals(0, harness.target.clears.get());
		}
	}

	@Test
	@Timeout(30)
	void installedFieldsAreClearedBeforeSuccessAndBothKindsOfVanillaFailureComplete() throws Exception {
		for (int mode = 0; mode < 3; mode++) {
			try (Harness harness = new Harness()) {
				SeededLeafJobOrchestrator.Attempt attempt = harness.readyAttempt();
				CompletableFuture<String> vanilla = new CompletableFuture<>();
				CountDownLatch started = new CountDownLatch(1);
				AtomicInteger calls = new AtomicInteger();
				int failureMode = mode;
				CompletableFuture<String> completed = SeededLeafGenerationContinuation.<String>continueGeneration(
					harness.orchestrator, attempt, harness.target, harness.generation, () -> {
						calls.incrementAndGet();
						assertTrue(harness.target.installed != null);
						assertEquals("SeededLeafGenerationTest", Thread.currentThread().getName());
						started.countDown();
						if (failureMode == 1) { throw new IllegalStateException("synchronous failure after installation"); }
						return vanilla;
					}).toCompletableFuture();
				assertTrue(started.await(3, TimeUnit.SECONDS));
				if (mode == 0) {
					assertFalse(completed.isDone());
					assertEquals(0, harness.target.clears.get());
					vanilla.complete("assisted");
					assertEquals("assisted", completed.get(3, TimeUnit.SECONDS));
				} else {
					if (mode == 2) { vanilla.completeExceptionally(new IllegalStateException("asynchronous failure")); }
					assertThrows(java.util.concurrent.ExecutionException.class, () -> completed.get(3, TimeUnit.SECONDS));
				}
				assertEquals(1, calls.get());
				assertEquals(1, harness.target.installs.get());
				assertEquals(1, harness.target.clears.get());
				assertTrue(harness.target.installed == null, "cleanup must precede returned-stage completion");
			}
		}
	}

	@Test
	@Timeout(15)
	void failedInstallationClearsBeforeCallingLocalVanillaAndNeverRetriesIt() throws Exception {
		try (Harness harness = new Harness()) {
			harness.target.rejectInstallation = true;
			AtomicInteger calls = new AtomicInteger();
			CompletableFuture<String> completed = continuation(harness, harness.readyAttempt(), () -> {
				assertTrue(harness.target.installed == null);
				assertEquals(1, harness.target.clears.get());
				calls.incrementAndGet();
				return CompletableFuture.completedFuture("local after install rejection");
			}).future;
			assertEquals("local after install rejection", completed.get(3, TimeUnit.SECONDS));
			assertEquals(1, calls.get());
		}
	}

	@Test
	@Timeout(15)
	void reloadWhileGenerationContinuationIsQueuedUsesUntouchedLocalOperation() throws Exception {
		CountDownLatch blocked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (Harness harness = new Harness()) {
			harness.generation.execute(() -> {
				blocked.countDown();
				try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
			});
			assertTrue(blocked.await(2, TimeUnit.SECONDS));
			AtomicInteger calls = new AtomicInteger();
			CompletableFuture<String> completion = continuation(harness, harness.readyAttempt(), () -> {
				calls.incrementAndGet();
				assertTrue(harness.target.installed == null);
				return CompletableFuture.completedFuture("local after reload");
			}).future;
			harness.orchestrator.reload();
			release.countDown();
			assertEquals("local after reload", completion.get(3, TimeUnit.SECONDS));
			assertEquals(1, calls.get());
			assertEquals(0, harness.target.installs.get());
		} finally {
			release.countDown();
		}
	}

	private static CompletionStageResult<String> continuation(
		Harness harness, SeededLeafJobOrchestrator.Attempt attempt, java.util.function.Supplier<CompletableFuture<String>> vanilla
	) {
		return new CompletionStageResult<>(SeededLeafGenerationContinuation.continueGeneration(
			harness.orchestrator, attempt, harness.target, harness.generation, vanilla
		).toCompletableFuture());
	}

	private static SeededLeafJobOrchestrator.Result result(SeededLeafJobOrchestrator.Attempt attempt) throws Exception {
		return attempt.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
	}

	private record CompletionStageResult<T>(CompletableFuture<T> future) { }

	private static final class Harness implements AutoCloseable {
		private final SeededLeafJobAuthority authority = TestSeededLeafFixtures.authority(8, 100_000);
		private final SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(authority, 32, Duration.ofSeconds(5));
		private final ExecutorService generation = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "SeededLeafGenerationTest");
			thread.setDaemon(true);
			return thread;
		});
		private final Target target = new Target();

		private SeededLeafJobOrchestrator.RecordingRequest request() { return TestSeededLeafFixtures.request(); }
		private SeededLeafJobOrchestrator.ClientExchange exchange() { return TestSeededLeafFixtures.pendingExchange(); }

		private SeededLeafJobOrchestrator.Attempt readyAttempt() throws Exception {
			HolderLookup.Provider registries = VanillaRegistries.createLookup();
			SeededLeafJobOrchestrator.ClientExchange exchange = new SeededLeafJobOrchestrator.ClientExchange() {
				@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
					SeededLeafJobOrchestrator.Connection owner, AuthorizedSeededLeafJob job
				) {
					return CompletableFuture.supplyAsync(() -> SeededLeafDensityResultEnvelope.encode(
						SeededLeafClientTestBridge.compute(registries, OVERWORLD, job)));
				}
				@Override public void cancel(SeededLeafJobOrchestrator.Connection owner, SeededLeafJobClaim claim) { }
			};
			SeededLeafJobOrchestrator.Attempt attempt = orchestrator.submit(orchestrator.connect(OWNER), request(), exchange);
			assertEquals(SeededLeafJobOrchestrator.Status.READY, result(attempt).status());
			return attempt;
		}

		@Override public void close() {
			generation.shutdownNow();
			orchestrator.close();
		}
	}

	private static final class Target implements RemoteDensityTarget {
		private final AtomicInteger installs = new AtomicInteger();
		private final AtomicInteger clears = new AtomicInteger();
		private volatile RemoteDensityField installed;
		private boolean rejectInstallation;
		@Override public void worldgenAssist$installRemoteDensity(RemoteDensityField field) {
			installs.incrementAndGet();
			if (rejectInstallation) { throw new IllegalArgumentException("synthetic geometry mismatch"); }
			installed = field;
		}
		@Override public void worldgenAssist$clearRemoteDensity(UUID jobId) {
			if (installed != null && installed.jobId().equals(jobId)) { installed = null; }
			clears.incrementAndGet();
		}
	}

	private static final class TestSeededLeafFixtures {
		private TestSeededLeafFixtures() { }

		private static SeededLeafJobAuthority authority(int maximumTasks, int disclosedEntries) {
			AtomicInteger sequence = new AtomicInteger();
			byte[] key = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
			Arrays.fill(key, (byte)0x19);
			SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
				maximumTasks, maximumTasks, 100_000, disclosedEntries, Duration.ofSeconds(10), Duration.ofSeconds(10),
				System::nanoTime, () -> new UUID(0L, sequence.incrementAndGet()),
				() -> OpaqueWorldgenContextId.fromHex("cd".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
				() -> SeededLeafJobAuthenticator.fromKey(key)
			);
			authority.start();
			return authority;
		}

		private static SeededLeafJobOrchestrator.RecordingRequest request() {
			HolderLookup.Provider registries = VanillaRegistries.createLookup();
			Holder.Reference<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
				.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
			HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
			return new SeededLeafJobOrchestrator.RecordingRequest(
				OVERWORLD, 10, -20, RandomState.create(settings.value(), noises, 8675309L), settings,
				NoiseSettings.create(-64, 16, 1, 2), 100_000
			);
		}

		private static SeededLeafJobOrchestrator.ClientExchange pendingExchange() {
			return new SeededLeafJobOrchestrator.ClientExchange() {
				@Override public CompletableFuture<SeededLeafDensityResultEnvelope> send(
					SeededLeafJobOrchestrator.Connection connection, AuthorizedSeededLeafJob job
				) { return new CompletableFuture<>(); }
				@Override public void cancel(SeededLeafJobOrchestrator.Connection connection, SeededLeafJobClaim claim) { }
			};
		}
	}
}
