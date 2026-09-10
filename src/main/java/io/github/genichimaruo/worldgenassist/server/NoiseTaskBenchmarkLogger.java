package io.github.genichimaruo.worldgenassist.server;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.minecraft.world.level.ChunkPos;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;

public final class NoiseTaskBenchmarkLogger {
	public static final String SYSTEM_PROPERTY = "worldgen_assist.noise_benchmark";
	public static final String ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_NOISE_BENCHMARK";
	private static final long CPU_TIME_UNAVAILABLE = -1L;
	private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
	private static final ThreadLocal<String> EXECUTION_ROUTE = new ThreadLocal<>();

	private NoiseTaskBenchmarkLogger() {
	}

	public static boolean isEnabled() {
		return Boolean.getBoolean(SYSTEM_PROPERTY) || "true".equalsIgnoreCase(System.getenv(ENVIRONMENT_VARIABLE));
	}

	public static <T> Supplier<T> wrap(ChunkPos chunkPos, String backend, Supplier<T> task) {
		return wrap(chunkPos, backend, task, isEnabled());
	}

	static <T> Supplier<T> wrap(ChunkPos chunkPos, String backend, Supplier<T> task, boolean enabled) {
		Objects.requireNonNull(chunkPos, "chunkPos");
		Objects.requireNonNull(backend, "backend");
		Objects.requireNonNull(task, "task");
		if (!enabled) {
			return task;
		}

		long scheduledNanos = System.nanoTime();
		return wrapForMeasurement(
			scheduledNanos,
			System::nanoTime,
			NoiseTaskBenchmarkLogger::currentThreadCpuTime,
			task,
			measurement -> log(chunkPos, backend, measurement)
		);
	}

	static <T> Supplier<T> wrapForMeasurement(
		long scheduledNanos,
		LongSupplier nanoTime,
		Supplier<T> task,
		Consumer<Measurement> observer
	) {
		return wrapForMeasurement(scheduledNanos, nanoTime, () -> CPU_TIME_UNAVAILABLE, task, observer);
	}

	static <T> Supplier<T> wrapForMeasurement(
		long scheduledNanos,
		LongSupplier nanoTime,
		LongSupplier currentThreadCpuTime,
		Supplier<T> task,
		Consumer<Measurement> observer
	) {
		Objects.requireNonNull(nanoTime, "nanoTime");
		Objects.requireNonNull(currentThreadCpuTime, "currentThreadCpuTime");
		Objects.requireNonNull(task, "task");
		Objects.requireNonNull(observer, "observer");

		return () -> {
			long startedNanos = nanoTime.getAsLong();
			long startedCpuNanos = currentThreadCpuTime.getAsLong();
			T result;
			try {
				result = task.get();
			} catch (RuntimeException | Error error) {
				long completedCpuNanos = currentThreadCpuTime.getAsLong();
				long completedNanos = nanoTime.getAsLong();
				observer.accept(new Measurement(
					false,
					scheduledNanos,
					startedNanos,
					completedNanos,
					startedCpuNanos,
					completedCpuNanos,
					error
				));
				throw error;
			}
			long completedCpuNanos = currentThreadCpuTime.getAsLong();
			long completedNanos = nanoTime.getAsLong();
			observer.accept(new Measurement(
				true,
				scheduledNanos,
				startedNanos,
				completedNanos,
				startedCpuNanos,
				completedCpuNanos,
				null
			));
			return result;
		};
	}

	static void runWithExecutionRoute(String executionRoute, Runnable command) {
		Objects.requireNonNull(executionRoute, "executionRoute");
		Objects.requireNonNull(command, "command");
		String previousRoute = EXECUTION_ROUTE.get();
		EXECUTION_ROUTE.set(executionRoute);
		try {
			command.run();
		} finally {
			if (previousRoute == null) {
				EXECUTION_ROUTE.remove();
			} else {
				EXECUTION_ROUTE.set(previousRoute);
			}
		}
	}

	static String currentExecutionRoute(String requestedBackend) {
		String executionRoute = EXECUTION_ROUTE.get();
		if (executionRoute != null) {
			return executionRoute;
		}
		return "delegate".equals(requestedBackend) ? "vanilla_delegate" : requestedBackend;
	}

	private static void log(ChunkPos chunkPos, String backend, Measurement measurement) {
		double queueMillis = (measurement.startedNanos() - measurement.scheduledNanos()) / 1_000_000.0;
		double computeMillis = (measurement.completedNanos() - measurement.startedNanos()) / 1_000_000.0;
		long cpuNanos = measurement.cpuNanos();
		double cpuMillis = cpuNanos < 0L ? -1.0 : cpuNanos / 1_000_000.0;
		String event = measurement.success() ? "complete" : "failed";
		String executionRoute = currentExecutionRoute(backend);
		if (measurement.success()) {
			WorldgenAssist.LOGGER.info(
				"[CAWG] task.{} stage=noise backend={} execution={} chunk={},{} queue_ms={} compute_ms={} cpu_ms={} scheduled_nanos={} started_nanos={} completed_nanos={}",
				event,
				backend,
				executionRoute,
				chunkPos.x(),
				chunkPos.z(),
				queueMillis,
				computeMillis,
				cpuMillis,
				measurement.scheduledNanos(),
				measurement.startedNanos(),
				measurement.completedNanos()
			);
			return;
		}

		WorldgenAssist.LOGGER.warn(
			"[CAWG] task.{} stage=noise backend={} execution={} chunk={},{} queue_ms={} compute_ms={} cpu_ms={} scheduled_nanos={} started_nanos={} completed_nanos={}",
			event,
			backend,
			executionRoute,
			chunkPos.x(),
			chunkPos.z(),
			queueMillis,
			computeMillis,
			cpuMillis,
			measurement.scheduledNanos(),
			measurement.startedNanos(),
			measurement.completedNanos(),
			measurement.failure()
		);
	}

	private static long currentThreadCpuTime() {
		if (!THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported() || !THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
			return CPU_TIME_UNAVAILABLE;
		}
		return THREAD_MX_BEAN.getCurrentThreadCpuTime();
	}

	record Measurement(
		boolean success,
		long scheduledNanos,
		long startedNanos,
		long completedNanos,
		long startedCpuNanos,
		long completedCpuNanos,
		Throwable failure
	) {
		long cpuNanos() {
			if (startedCpuNanos < 0L || completedCpuNanos < 0L) {
				return CPU_TIME_UNAVAILABLE;
			}
			return completedCpuNanos - startedCpuNanos;
		}
	}
}
