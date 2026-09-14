package io.github.genichimaruo.worldgenassist.server;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public record RemoteWorldgenConfig(
	boolean enabled,
	SeedDisclosureMode seedDisclosureMode,
	int maxInFlightJobs,
	Duration jobTimeout,
	int cacheEntries,
	boolean predictionEnabled,
	int predictionIntervalTicks,
	int predictionLeadChunks,
	int validationSampleCells
) {
	public static final String ENABLED_SYSTEM_PROPERTY = "worldgen_assist.remote";
	public static final String ENABLED_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE";
	public static final String SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY = "worldgen_assist.remote.seed_disclosure";
	public static final String SEED_DISCLOSURE_MODE_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE";
	public static final String MAX_IN_FLIGHT_SYSTEM_PROPERTY = "worldgen_assist.remote.max_in_flight";
	public static final String MAX_IN_FLIGHT_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT";
	public static final String TIMEOUT_MILLIS_SYSTEM_PROPERTY = "worldgen_assist.remote.timeout_ms";
	public static final String TIMEOUT_MILLIS_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS";
	public static final String CACHE_ENTRIES_SYSTEM_PROPERTY = "worldgen_assist.remote.cache_entries";
	public static final String CACHE_ENTRIES_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES";
	public static final String PREDICTION_ENABLED_SYSTEM_PROPERTY = "worldgen_assist.remote.prediction";
	public static final String PREDICTION_ENABLED_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE_PREDICTION";
	public static final String PREDICTION_INTERVAL_TICKS_SYSTEM_PROPERTY = "worldgen_assist.remote.prediction_interval_ticks";
	public static final String PREDICTION_INTERVAL_TICKS_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE_PREDICTION_INTERVAL_TICKS";
	public static final String PREDICTION_LEAD_CHUNKS_SYSTEM_PROPERTY = "worldgen_assist.remote.prediction_lead_chunks";
	public static final String PREDICTION_LEAD_CHUNKS_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE_PREDICTION_LEAD_CHUNKS";
	public static final String VALIDATION_SAMPLE_CELLS_SYSTEM_PROPERTY = "worldgen_assist.remote.validation_sample_cells";
	public static final String VALIDATION_SAMPLE_CELLS_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS";

	public static final int DEFAULT_MAX_IN_FLIGHT_JOBS = 8;
	public static final int MAX_IN_FLIGHT_JOBS = 64;
	public static final long DEFAULT_TIMEOUT_MILLIS = 2_000L;
	public static final long MIN_TIMEOUT_MILLIS = 50L;
	public static final long MAX_TIMEOUT_MILLIS = 60_000L;
	public static final int DEFAULT_CACHE_ENTRIES = 16;
	public static final int MAX_CACHE_ENTRIES = 256;
	public static final int DEFAULT_PREDICTION_INTERVAL_TICKS = 20;
	public static final int MIN_PREDICTION_INTERVAL_TICKS = 1;
	public static final int MAX_PREDICTION_INTERVAL_TICKS = 1_200;
	public static final int DEFAULT_PREDICTION_LEAD_CHUNKS = 8;
	public static final int MAX_PREDICTION_LEAD_CHUNKS = 8;
	public static final int DEFAULT_VALIDATION_SAMPLE_CELLS = 0;
	public static final int MAX_VALIDATION_SAMPLE_CELLS = 64;

	public RemoteWorldgenConfig(boolean enabled, int maxInFlightJobs, Duration jobTimeout) {
		this(enabled, legacyMode(enabled), maxInFlightJobs, jobTimeout, DEFAULT_CACHE_ENTRIES, false, DEFAULT_PREDICTION_INTERVAL_TICKS, DEFAULT_PREDICTION_LEAD_CHUNKS, DEFAULT_VALIDATION_SAMPLE_CELLS);
	}

	public RemoteWorldgenConfig(boolean enabled, int maxInFlightJobs, Duration jobTimeout, int cacheEntries) {
		this(enabled, legacyMode(enabled), maxInFlightJobs, jobTimeout, cacheEntries, false, DEFAULT_PREDICTION_INTERVAL_TICKS, DEFAULT_PREDICTION_LEAD_CHUNKS, DEFAULT_VALIDATION_SAMPLE_CELLS);
	}

	public RemoteWorldgenConfig(
		boolean enabled,
		int maxInFlightJobs,
		Duration jobTimeout,
		int cacheEntries,
		boolean predictionEnabled,
		int predictionIntervalTicks,
		int predictionLeadChunks
	) {
		this(
			enabled,
			legacyMode(enabled),
			maxInFlightJobs,
			jobTimeout,
			cacheEntries,
			predictionEnabled,
			predictionIntervalTicks,
			predictionLeadChunks,
			DEFAULT_VALIDATION_SAMPLE_CELLS
		);
	}

	public RemoteWorldgenConfig(
		boolean enabled,
		int maxInFlightJobs,
		Duration jobTimeout,
		int cacheEntries,
		boolean predictionEnabled,
		int predictionIntervalTicks,
		int predictionLeadChunks,
		int validationSampleCells
	) {
		this(
			enabled,
			legacyMode(enabled),
			maxInFlightJobs,
			jobTimeout,
			cacheEntries,
			predictionEnabled,
			predictionIntervalTicks,
			predictionLeadChunks,
			validationSampleCells
		);
	}

	public RemoteWorldgenConfig {
		Objects.requireNonNull(seedDisclosureMode, "seedDisclosureMode");
		Objects.requireNonNull(jobTimeout, "jobTimeout");
		if (maxInFlightJobs < 1 || maxInFlightJobs > MAX_IN_FLIGHT_JOBS) {
			throw new IllegalArgumentException(
				"maxInFlightJobs must be between 1 and " + MAX_IN_FLIGHT_JOBS + ": " + maxInFlightJobs
			);
		}
		if (cacheEntries < 0 || cacheEntries > MAX_CACHE_ENTRIES) {
			throw new IllegalArgumentException(
				"cacheEntries must be between 0 and " + MAX_CACHE_ENTRIES + ": " + cacheEntries
			);
		}
		if (predictionIntervalTicks < MIN_PREDICTION_INTERVAL_TICKS || predictionIntervalTicks > MAX_PREDICTION_INTERVAL_TICKS) {
			throw new IllegalArgumentException(
				"predictionIntervalTicks must be between " + MIN_PREDICTION_INTERVAL_TICKS + " and "
					+ MAX_PREDICTION_INTERVAL_TICKS + ": " + predictionIntervalTicks
			);
		}
		if (predictionLeadChunks < 1 || predictionLeadChunks > MAX_PREDICTION_LEAD_CHUNKS) {
			throw new IllegalArgumentException(
				"predictionLeadChunks must be between 1 and " + MAX_PREDICTION_LEAD_CHUNKS + ": " + predictionLeadChunks
			);
		}
		if (predictionEnabled && cacheEntries == 0) {
			throw new IllegalArgumentException("Remote prediction requires at least one cache entry");
		}
		if (validationSampleCells < 0 || validationSampleCells > MAX_VALIDATION_SAMPLE_CELLS) {
			throw new IllegalArgumentException(
				"validationSampleCells must be between 0 and " + MAX_VALIDATION_SAMPLE_CELLS + ": " + validationSampleCells
			);
		}
		long timeoutMillis = jobTimeout.toMillis();
		if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
			throw new IllegalArgumentException(
				"jobTimeout must be between " + MIN_TIMEOUT_MILLIS + " and " + MAX_TIMEOUT_MILLIS + " milliseconds: " + timeoutMillis
			);
		}
	}

	public static RemoteWorldgenConfig current() {
		boolean enabled = Boolean.getBoolean(ENABLED_SYSTEM_PROPERTY)
			|| "true".equalsIgnoreCase(System.getenv(ENABLED_ENVIRONMENT_VARIABLE));
		SeedDisclosureMode seedDisclosureMode = readSeedDisclosureMode();
		int maxInFlight = readInt(MAX_IN_FLIGHT_SYSTEM_PROPERTY, MAX_IN_FLIGHT_ENVIRONMENT_VARIABLE, DEFAULT_MAX_IN_FLIGHT_JOBS);
		long timeoutMillis = readLong(TIMEOUT_MILLIS_SYSTEM_PROPERTY, TIMEOUT_MILLIS_ENVIRONMENT_VARIABLE, DEFAULT_TIMEOUT_MILLIS);
		int cacheEntries = readInt(CACHE_ENTRIES_SYSTEM_PROPERTY, CACHE_ENTRIES_ENVIRONMENT_VARIABLE, DEFAULT_CACHE_ENTRIES);
		boolean predictionEnabled = readBoolean(PREDICTION_ENABLED_SYSTEM_PROPERTY, PREDICTION_ENABLED_ENVIRONMENT_VARIABLE, false);
		int predictionIntervalTicks = readInt(
			PREDICTION_INTERVAL_TICKS_SYSTEM_PROPERTY,
			PREDICTION_INTERVAL_TICKS_ENVIRONMENT_VARIABLE,
			DEFAULT_PREDICTION_INTERVAL_TICKS
		);
		int predictionLeadChunks = readInt(
			PREDICTION_LEAD_CHUNKS_SYSTEM_PROPERTY,
			PREDICTION_LEAD_CHUNKS_ENVIRONMENT_VARIABLE,
			DEFAULT_PREDICTION_LEAD_CHUNKS
		);
		int validationSampleCells = readInt(
			VALIDATION_SAMPLE_CELLS_SYSTEM_PROPERTY,
			VALIDATION_SAMPLE_CELLS_ENVIRONMENT_VARIABLE,
			DEFAULT_VALIDATION_SAMPLE_CELLS
		);
		return new RemoteWorldgenConfig(
			enabled,
			seedDisclosureMode,
			maxInFlight,
			Duration.ofMillis(timeoutMillis),
			cacheEntries,
			predictionEnabled,
			predictionIntervalTicks,
			predictionLeadChunks,
			validationSampleCells
		);
	}

	/**
	 * Returns whether the current protocol may dispatch a request containing the
	 * raw world seed. Enabling remote execution alone is intentionally
	 * insufficient so a copied benchmark configuration cannot disclose a seed by
	 * accident.
	 */
	public boolean remoteExecutionEnabled() {
		return enabled && seedDisclosureMode == SeedDisclosureMode.TRUSTED_RAW;
	}

	private static SeedDisclosureMode legacyMode(boolean enabled) {
		return enabled ? SeedDisclosureMode.TRUSTED_RAW : SeedDisclosureMode.DENY;
	}

	private static SeedDisclosureMode readSeedDisclosureMode() {
		String value = readValue(SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY, SEED_DISCLOSURE_MODE_ENVIRONMENT_VARIABLE);
		if (value == null) {
			return SeedDisclosureMode.DENY;
		}
		try {
			return SeedDisclosureMode.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
				"Invalid seed disclosure mode for " + SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY
					+ ": " + value + " (expected deny or trusted_raw)",
				exception
			);
		}
	}

	private static boolean readBoolean(String property, String environmentVariable, boolean defaultValue) {
		String value = readValue(property, environmentVariable);
		return value == null ? defaultValue : Boolean.parseBoolean(value);
	}

	private static int readInt(String property, String environmentVariable, int defaultValue) {
		String value = readValue(property, environmentVariable);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid integer for " + property + ": " + value, exception);
		}
	}

	private static long readLong(String property, String environmentVariable, long defaultValue) {
		String value = readValue(property, environmentVariable);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid integer for " + property + ": " + value, exception);
		}
	}

	private static String readValue(String property, String environmentVariable) {
		String propertyValue = System.getProperty(property);
		if (propertyValue != null && !propertyValue.isBlank()) {
			return propertyValue.trim();
		}
		String environmentValue = System.getenv(environmentVariable);
		return environmentValue == null || environmentValue.isBlank() ? null : environmentValue.trim();
	}

	public enum SeedDisclosureMode {
		DENY,
		TRUSTED_RAW
	}
}
