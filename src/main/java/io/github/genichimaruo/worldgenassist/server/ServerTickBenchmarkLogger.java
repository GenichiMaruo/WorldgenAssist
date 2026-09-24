package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;

public final class ServerTickBenchmarkLogger {
	public static final String SYSTEM_PROPERTY = "worldgen_assist.tick_benchmark";
	public static final String ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_TICK_BENCHMARK";
	public static final long TICK_BUDGET_NANOS = 50_000_000L;

	private final LongSupplier nanoTime;
	private final Supplier<WorldgenStageMetrics.Snapshot> noiseMetrics;
	private final Supplier<BackendSaturation> backendSaturation;
	private final Consumer<Measurement> observer;
	private long tick;
	private boolean started;
	private long startedNanos;
	private WorldgenStageMetrics.Snapshot noiseAtStart;
	private WorldgenStageMetrics.Snapshot noiseAtPreviousEnd;
	private BackendSaturation backendAtPreviousEnd;

	ServerTickBenchmarkLogger(
		LongSupplier nanoTime,
		Supplier<WorldgenStageMetrics.Snapshot> noiseMetrics,
		Supplier<BackendSaturation> backendSaturation,
		Consumer<Measurement> observer
	) {
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
		this.noiseMetrics = Objects.requireNonNull(noiseMetrics, "noiseMetrics");
		this.backendSaturation = Objects.requireNonNull(backendSaturation, "backendSaturation");
		this.observer = Objects.requireNonNull(observer, "observer");
	}

	public static boolean isEnabled() {
		return Boolean.getBoolean(SYSTEM_PROPERTY) || "true".equalsIgnoreCase(System.getenv(ENVIRONMENT_VARIABLE));
	}

	public static ServerTickBenchmarkLogger create(NoiseStageBackendConfig backendConfig) {
		Objects.requireNonNull(backendConfig, "backendConfig");
		Supplier<BackendSaturation> saturation = backendConfig.mode() == NoiseStageBackendConfig.Mode.LOCAL
			? () -> BackendSaturation.from(LocalWorldgenTaskBackend.instance().sampleSaturationAndResetPeaks())
			: BackendSaturation::unavailable;
		return new ServerTickBenchmarkLogger(
			System::nanoTime,
			WorldgenStageMetrics.NOISE::snapshot,
			saturation,
			ServerTickBenchmarkLogger::log
		);
	}

	public void reset() {
		tick = 0L;
		started = false;
		startedNanos = 0L;
		noiseAtStart = null;
		noiseAtPreviousEnd = null;
		backendAtPreviousEnd = null;
	}

	public void onStartTick() {
		tick++;
		startedNanos = nanoTime.getAsLong();
		noiseAtStart = noiseMetrics.get();
		started = true;
	}

	public void onEndTick() {
		if (!started) {
			return;
		}

		long completedNanos = nanoTime.getAsLong();
		WorldgenStageMetrics.Snapshot noiseAtEnd = noiseMetrics.get();
		BackendSaturation backendAtEnd = backendSaturation.get();
		WorldgenStageMetrics.Snapshot noiseBaseline = noiseAtPreviousEnd == null ? noiseAtStart : noiseAtPreviousEnd;
		long elapsedNanos = Math.max(0L, completedNanos - startedNanos);
		Measurement measurement = new Measurement(
			tick,
			startedNanos,
			completedNanos,
			elapsedNanos,
			elapsedNanos > TICK_BUDGET_NANOS,
			Math.max(0L, noiseAtEnd.generatedChunks() - noiseBaseline.generatedChunks()),
			Math.max(0L, noiseAtEnd.failures() - noiseBaseline.failures()),
			noiseAtStart.activeOperations(),
			noiseAtEnd.activeOperations(),
			backendAtEnd,
			backendAtPreviousEnd == null
				? 0L
				: Math.max(0L, backendAtEnd.vanillaFallbacks() - backendAtPreviousEnd.vanillaFallbacks())
		);
		noiseAtPreviousEnd = noiseAtEnd;
		backendAtPreviousEnd = backendAtEnd;
		started = false;
		observer.accept(measurement);
	}

	private static void log(Measurement measurement) {
		BackendSaturation backend = measurement.backend();
		WorldgenAssist.LOGGER.info(
			"[CAWG] tick.complete tick={} elapsed_ms={} over_budget={} budget_ms={} started_nanos={} completed_nanos={} noise_completed={} noise_failed={} noise_active_start={} noise_active_end={} local_parallelism={} local_pool_size={} local_active={} local_running={} local_queued={} local_admitted={} local_admission_capacity={} local_task_active={} local_peak_task_active={} local_peak_admitted={} local_fallbacks_delta={}",
			measurement.tick(),
			measurement.elapsedNanos() / 1_000_000.0,
			measurement.overBudget(),
			TICK_BUDGET_NANOS / 1_000_000.0,
			measurement.startedNanos(),
			measurement.completedNanos(),
			measurement.noiseCompleted(),
			measurement.noiseFailed(),
			measurement.noiseActiveStart(),
			measurement.noiseActiveEnd(),
			backend.parallelism(),
			backend.poolSize(),
			backend.activeThreads(),
			backend.runningThreads(),
			backend.queuedTasks(),
			backend.admittedTasks(),
			backend.admissionCapacity(),
			backend.taskActive(),
			backend.peakTaskActive(),
			backend.peakAdmitted(),
			measurement.localFallbacksDelta()
		);
	}

	record BackendSaturation(
		int parallelism,
		int poolSize,
		int activeThreads,
		int runningThreads,
		int queuedTasks,
		int admittedTasks,
		int admissionCapacity,
		int taskActive,
		int peakTaskActive,
		int peakAdmitted,
		long vanillaFallbacks
	) {
		static BackendSaturation from(LocalWorldgenTaskBackend.SaturationSample sample) {
			LocalWorldgenTaskBackend.Snapshot snapshot = sample.snapshot();
			return new BackendSaturation(
				snapshot.parallelism(),
				snapshot.poolSize(),
				snapshot.activeThreads(),
				snapshot.runningThreads(),
				snapshot.queuedTasks(),
				snapshot.admittedTasks(),
				snapshot.admissionCapacity(),
				snapshot.activeTasks(),
				sample.peakActiveTasks(),
				sample.peakAdmittedTasks(),
				snapshot.vanillaFallbacks()
			);
		}

		static BackendSaturation unavailable() {
			return new BackendSaturation(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0L);
		}
	}

	record Measurement(
		long tick,
		long startedNanos,
		long completedNanos,
		long elapsedNanos,
		boolean overBudget,
		long noiseCompleted,
		long noiseFailed,
		long noiseActiveStart,
		long noiseActiveEnd,
		BackendSaturation backend,
		long localFallbacksDelta
	) {
	}
}
