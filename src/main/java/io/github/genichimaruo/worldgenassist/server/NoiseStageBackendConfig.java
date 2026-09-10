package io.github.genichimaruo.worldgenassist.server;

import java.util.Locale;

public record NoiseStageBackendConfig(Mode mode, int workerThreads, int queuedTasksPerWorker) {
	public static final String MODE_SYSTEM_PROPERTY = "worldgen_assist.noise_backend";
	public static final String MODE_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_NOISE_BACKEND";
	public static final String WORKERS_SYSTEM_PROPERTY = "worldgen_assist.local_workers";
	public static final String WORKERS_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_LOCAL_WORKERS";
	public static final String QUEUE_PER_WORKER_SYSTEM_PROPERTY = "worldgen_assist.local_queue_per_worker";
	public static final String QUEUE_PER_WORKER_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_LOCAL_QUEUE_PER_WORKER";
	public static final int DEFAULT_WORKER_THREADS = 1;
	public static final int MAX_WORKER_THREADS = 64;
	public static final int DEFAULT_QUEUED_TASKS_PER_WORKER = 1;
	public static final int MAX_QUEUED_TASKS_PER_WORKER = 16;

	private static final NoiseStageBackendConfig CURRENT = load();

	public static NoiseStageBackendConfig current() {
		return CURRENT;
	}

	static Mode parseMode(String value) {
		if (value == null || value.isBlank()) {
			return Mode.VANILLA;
		}

		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "local" -> Mode.LOCAL;
			case "delegate" -> Mode.DELEGATE;
			case "vanilla" -> Mode.VANILLA;
			default -> Mode.VANILLA;
		};
	}

	static int parseWorkerThreads(String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT_WORKER_THREADS;
		}

		try {
			int parsed = Integer.parseInt(value.trim());
			return parsed >= 1 && parsed <= MAX_WORKER_THREADS ? parsed : DEFAULT_WORKER_THREADS;
		} catch (NumberFormatException ignored) {
			return DEFAULT_WORKER_THREADS;
		}
	}

	static int parseQueuedTasksPerWorker(String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT_QUEUED_TASKS_PER_WORKER;
		}

		try {
			int parsed = Integer.parseInt(value.trim());
			return parsed >= 0 && parsed <= MAX_QUEUED_TASKS_PER_WORKER
				? parsed
				: DEFAULT_QUEUED_TASKS_PER_WORKER;
		} catch (NumberFormatException ignored) {
			return DEFAULT_QUEUED_TASKS_PER_WORKER;
		}
	}

	private static NoiseStageBackendConfig load() {
		String modeValue = configuredValue(MODE_SYSTEM_PROPERTY, MODE_ENVIRONMENT_VARIABLE);
		String workersValue = configuredValue(WORKERS_SYSTEM_PROPERTY, WORKERS_ENVIRONMENT_VARIABLE);
		String queuePerWorkerValue = configuredValue(
			QUEUE_PER_WORKER_SYSTEM_PROPERTY,
			QUEUE_PER_WORKER_ENVIRONMENT_VARIABLE
		);
		return new NoiseStageBackendConfig(
			parseMode(modeValue),
			parseWorkerThreads(workersValue),
			parseQueuedTasksPerWorker(queuePerWorkerValue)
		);
	}

	private static String configuredValue(String systemProperty, String environmentVariable) {
		String propertyValue = System.getProperty(systemProperty);
		return propertyValue == null || propertyValue.isBlank() ? System.getenv(environmentVariable) : propertyValue;
	}

	public enum Mode {
		VANILLA("vanilla"),
		DELEGATE("delegate"),
		LOCAL("local");

		private final String id;

		Mode(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}
	}
}
