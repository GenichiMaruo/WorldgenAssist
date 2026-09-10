package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class ServerTickBenchmarkLoggerTest {
	@Test
	void recordsTickDurationNoiseProgressAndLocalSaturation() {
		AtomicReference<ServerTickBenchmarkLogger.Measurement> observed = new AtomicReference<>();
		ServerTickBenchmarkLogger logger = new ServerTickBenchmarkLogger(
			clock(100L, 60_000_100L),
			sequence(
				new WorldgenStageMetrics.Snapshot(10L, 1L, 1_000L, 3L, 7L),
				new WorldgenStageMetrics.Snapshot(12L, 2L, 2_000L, 1L, 8L)
			),
			sequence(
				new ServerTickBenchmarkLogger.BackendSaturation(19, 19, 9, 8, 6, 15, 1043, 9, 19, 35, 5L)
			),
			observed::set
		);

		logger.onStartTick();
		logger.onEndTick();

		ServerTickBenchmarkLogger.Measurement measurement = observed.get();
		assertEquals(1L, measurement.tick());
		assertEquals(60_000_000L, measurement.elapsedNanos());
		assertTrue(measurement.overBudget());
		assertEquals(2L, measurement.noiseCompleted());
		assertEquals(1L, measurement.noiseFailed());
		assertEquals(3L, measurement.noiseActiveStart());
		assertEquals(1L, measurement.noiseActiveEnd());
		assertEquals(9, measurement.backend().activeThreads());
		assertEquals(6, measurement.backend().queuedTasks());
		assertEquals(15, measurement.backend().admittedTasks());
		assertEquals(19, measurement.backend().peakTaskActive());
		assertEquals(35, measurement.backend().peakAdmitted());
		assertEquals(0L, measurement.localFallbacksDelta());
	}

	@Test
	void ignoresAnEndEventWithoutAStartEventAndResetsTickSequence() {
		AtomicInteger observations = new AtomicInteger();
		ServerTickBenchmarkLogger logger = new ServerTickBenchmarkLogger(
			clock(10L, 20L, 30L),
			() -> new WorldgenStageMetrics.Snapshot(0L, 0L, 0L, 0L, 0L),
			ServerTickBenchmarkLogger.BackendSaturation::unavailable,
			measurement -> observations.incrementAndGet()
		);

		logger.onEndTick();
		assertEquals(0, observations.get());

		logger.onStartTick();
		logger.reset();
		logger.onEndTick();
		assertEquals(0, observations.get());
	}

	@Test
	void clampsARegressingMonotonicClockToZero() {
		AtomicReference<ServerTickBenchmarkLogger.Measurement> observed = new AtomicReference<>();
		ServerTickBenchmarkLogger logger = new ServerTickBenchmarkLogger(
			clock(50L, 40L),
			() -> new WorldgenStageMetrics.Snapshot(0L, 0L, 0L, 0L, 0L),
			ServerTickBenchmarkLogger.BackendSaturation::unavailable,
			observed::set
		);

		logger.onStartTick();
		logger.onEndTick();

		assertEquals(0L, observed.get().elapsedNanos());
		assertFalse(observed.get().overBudget());
		assertEquals(-1, observed.get().backend().parallelism());
	}

	@Test
	void carriesCompletionDeltasAcrossTheGapBetweenTicks() {
		AtomicReference<ServerTickBenchmarkLogger.Measurement> observed = new AtomicReference<>();
		ServerTickBenchmarkLogger logger = new ServerTickBenchmarkLogger(
			clock(10L, 20L, 30L, 40L),
			sequence(
				new WorldgenStageMetrics.Snapshot(0L, 0L, 0L, 2L, 2L),
				new WorldgenStageMetrics.Snapshot(0L, 0L, 0L, 2L, 2L),
				new WorldgenStageMetrics.Snapshot(2L, 0L, 0L, 0L, 2L),
				new WorldgenStageMetrics.Snapshot(2L, 0L, 0L, 0L, 2L)
			),
			ServerTickBenchmarkLogger.BackendSaturation::unavailable,
			observed::set
		);

		logger.onStartTick();
		logger.onEndTick();
		assertEquals(0L, observed.get().noiseCompleted());

		logger.onStartTick();
		logger.onEndTick();
		assertEquals(2L, observed.get().noiseCompleted());
		assertEquals(0L, observed.get().noiseActiveStart());
	}

	private static java.util.function.LongSupplier clock(long... values) {
		AtomicInteger index = new AtomicInteger();
		return () -> values[index.getAndIncrement()];
	}

	@SafeVarargs
	private static <T> Supplier<T> sequence(T... values) {
		AtomicInteger index = new AtomicInteger();
		return () -> values[index.getAndIncrement()];
	}
}
