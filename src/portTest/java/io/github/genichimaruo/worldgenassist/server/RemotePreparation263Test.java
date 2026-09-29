package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import io.github.genichimaruo.worldgenassist.common.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RemotePreparation263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void preparesBeforeAnswerAndRejectsMutation() {
		var lookup = VanillaRegistries.createWorldLookup();
		var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
		var noise = settings.value().noiseSettings();
		var dimension = Identifier.parse("minecraft:the_nether");
		var fingerprint = WorldgenContextFingerprintFactory.create(lookup, dimension, 8675309L, true, noise.minY(), noise.height(), settings);
		var job = new TerrainDensityJob(new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), dimension, -2, 5, fingerprint),
			8675309L, true, NoiseGeneratorSettings.NETHER.identifier(), noise.minY(), noise.height(), 1, 1);
		var state = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), job.worldSeed(), settings.value());
		var prepared = RemoteDensityValidator.prepare(job, 8, state, settings.value(), noise, new java.util.Random(1));
		double[] values = new double[job.sampleCount()];
		try (var buffer = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED).get(settings.value().noiseRouter().finalDensity())
			.sampleVolume(new DensityVolume(16, noise.height(), 16, -32, noise.minY(), 80))) {
			for (int i = 0; i < values.length; i++) values[i] = buffer.get(i);
		}
		assertEquals(1024, prepared.compare(new TerrainDensityResult(job.identity(), values, 0)).sampledValues());
		for (int i = 0; i < values.length; i++) values[i] = Math.nextUp((float) values[i]);
		assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,
			() -> prepared.compare(new TerrainDensityResult(job.identity(), values, 0)));
	}

	@Test void comparisonWaitsForBothInputsAndQueueCancellationReleasesAdmission() {
		ArrayDeque<Runnable> queue = new ArrayDeque<>();
		var pipeline = new BoundedRemotePreparation<Integer, Integer>(2, queue::add);
		UUID owner = UUID.randomUUID();
		var ticket = pipeline.reserve(owner, 1);
		assertNull(pipeline.reserve(owner, 1));
		var remote = new CompletableFuture<Integer>();
		AtomicInteger comparisons = new AtomicInteger();
		var answer = ticket.start(() -> 7, remote, (actual, expected) -> { comparisons.incrementAndGet(); assertEquals(expected, actual); return actual; });
		queue.remove().run();
		assertFalse(answer.isDone());
		remote.complete(7);
		assertEquals(7, answer.join()); assertEquals(1, comparisons.get()); assertEquals(0, pipeline.size());
		var cancelled = pipeline.reserve(owner, 1);
		var never = cancelled.start(() -> fail("Cancelled queued preparation ran"), new CompletableFuture<>(), (a,b) -> a);
		cancelled.cancel();
		assertTrue(never.isCompletedExceptionally()); assertEquals(0, pipeline.size());
		queue.remove().run();
	}

	@Test void runningCancelledPreparationKeepsItsSlotUntilInvocationExits() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(1), exit = new CountDownLatch(1), released = new CountDownLatch(1);
		try {
			var pipeline = new BoundedRemotePreparation<Integer, Integer>(1, runnable -> executor.execute(() -> { try { runnable.run(); } finally { released.countDown(); } }));
			UUID owner = UUID.randomUUID();
			var ticket = pipeline.reserve(owner, 1);
			var answer = ticket.start(() -> {
				entered.countDown();
				boolean finished = false;
				while (!finished) try { exit.await(); finished = true; } catch (InterruptedException ignored) { }
				return 3;
			}, new CompletableFuture<>(), (a,b) -> a);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			ticket.cancel();
			assertTrue(answer.isCompletedExceptionally()); assertNull(pipeline.reserve(owner, 1));
			exit.countDown(); assertTrue(released.await(5, TimeUnit.SECONDS));
			assertEquals(0, pipeline.size());
		} finally { exit.countDown(); executor.shutdownNow(); }
	}
}
