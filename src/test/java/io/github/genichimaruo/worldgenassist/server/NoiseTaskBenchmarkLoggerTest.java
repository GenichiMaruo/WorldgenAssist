package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.Test;

class NoiseTaskBenchmarkLoggerTest {
	@Test
	void returnsTheOriginalSupplierWhenBenchmarkingIsDisabled() {
		Supplier<String> original = () -> "done";

		assertSame(original, NoiseTaskBenchmarkLogger.wrap(new ChunkPos(1, -2), "vanilla", original, false));
	}

	@Test
	void recordsQueueAndComputeBoundariesWithoutCallingTheTaskEarly() {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<NoiseTaskBenchmarkLogger.Measurement> observed = new AtomicReference<>();
		LongSupplier clock = clock(1_250L, 1_900L);
		Supplier<String> wrapped = NoiseTaskBenchmarkLogger.wrapForMeasurement(
			1_000L,
			clock,
			() -> {
				calls.incrementAndGet();
				return "done";
			},
			observed::set
		);

		assertEquals(0, calls.get());
		assertEquals("done", wrapped.get());
		assertEquals(1, calls.get());
		assertEquals(
			new NoiseTaskBenchmarkLogger.Measurement(true, 1_000L, 1_250L, 1_900L, -1L, -1L, null),
			observed.get()
		);
	}

	@Test
	void recordsCurrentWorkerThreadCpuTimeWhenAvailable() {
		AtomicReference<NoiseTaskBenchmarkLogger.Measurement> observed = new AtomicReference<>();
		Supplier<String> wrapped = NoiseTaskBenchmarkLogger.wrapForMeasurement(
			1_000L,
			clock(1_250L, 1_900L),
			clock(400L, 775L),
			() -> "done",
			observed::set
		);

		assertEquals("done", wrapped.get());
		assertEquals(375L, observed.get().cpuNanos());
		assertEquals(
			new NoiseTaskBenchmarkLogger.Measurement(true, 1_000L, 1_250L, 1_900L, 400L, 775L, null),
			observed.get()
		);
	}

	@Test
	void recordsAndRethrowsTheOriginalFailure() {
		IllegalStateException failure = new IllegalStateException("failed");
		AtomicReference<NoiseTaskBenchmarkLogger.Measurement> observed = new AtomicReference<>();
		Supplier<String> wrapped = NoiseTaskBenchmarkLogger.wrapForMeasurement(
			10L,
			clock(20L, 45L),
			() -> {
				throw failure;
			},
			observed::set
		);

		assertSame(failure, assertThrows(IllegalStateException.class, wrapped::get));
		assertEquals(
			new NoiseTaskBenchmarkLogger.Measurement(false, 10L, 20L, 45L, -1L, -1L, failure),
			observed.get()
		);
	}

	@Test
	void scopesAndRestoresTheActualExecutionRoute() {
		AtomicReference<String> observed = new AtomicReference<>();
		assertEquals("local", NoiseTaskBenchmarkLogger.currentExecutionRoute("local"));
		assertEquals("vanilla_delegate", NoiseTaskBenchmarkLogger.currentExecutionRoute("delegate"));

		NoiseTaskBenchmarkLogger.runWithExecutionRoute("local_pool", () -> {
			observed.set(NoiseTaskBenchmarkLogger.currentExecutionRoute("local"));
			NoiseTaskBenchmarkLogger.runWithExecutionRoute(
				"vanilla_fallback",
				() -> assertEquals(
					"vanilla_fallback",
					NoiseTaskBenchmarkLogger.currentExecutionRoute("local")
				)
			);
			assertEquals("local_pool", NoiseTaskBenchmarkLogger.currentExecutionRoute("local"));
		});

		assertEquals("local_pool", observed.get());
		assertEquals("local", NoiseTaskBenchmarkLogger.currentExecutionRoute("local"));
	}

	private static LongSupplier clock(long... values) {
		AtomicInteger index = new AtomicInteger();
		return () -> values[index.getAndIncrement()];
	}
}
