package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoteWorldgenConfigTest {
	@AfterEach
	void clearProperties() {
		System.clearProperty(RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.MAX_IN_FLIGHT_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.TIMEOUT_MILLIS_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.CACHE_ENTRIES_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.PREDICTION_ENABLED_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.PREDICTION_INTERVAL_TICKS_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.PREDICTION_LEAD_CHUNKS_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.VALIDATION_SAMPLE_CELLS_SYSTEM_PROPERTY);
	}

	@Test
	void defaultsToDisabledAndBoundedSingleJob() {
		RemoteWorldgenConfig config = RemoteWorldgenConfig.current();

		assertFalse(config.enabled());
		assertEquals(RemoteWorldgenConfig.SeedDisclosureMode.DENY, config.seedDisclosureMode());
		assertFalse(config.remoteExecutionEnabled());
		assertEquals(RemoteWorldgenConfig.DEFAULT_MAX_IN_FLIGHT_JOBS, config.maxInFlightJobs());
		assertEquals(Duration.ofMillis(RemoteWorldgenConfig.DEFAULT_TIMEOUT_MILLIS), config.jobTimeout());
		assertEquals(RemoteWorldgenConfig.DEFAULT_CACHE_ENTRIES, config.cacheEntries());
		assertFalse(config.predictionEnabled());
		assertEquals(RemoteWorldgenConfig.DEFAULT_PREDICTION_INTERVAL_TICKS, config.predictionIntervalTicks());
		assertEquals(RemoteWorldgenConfig.DEFAULT_PREDICTION_LEAD_CHUNKS, config.predictionLeadChunks());
		assertEquals(RemoteWorldgenConfig.DEFAULT_VALIDATION_SAMPLE_CELLS, config.validationSampleCells());
	}

	@Test
	void parsesExplicitProperties() {
		System.setProperty(RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY, "true");
		System.setProperty(RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY, "trusted_raw");
		System.setProperty(RemoteWorldgenConfig.MAX_IN_FLIGHT_SYSTEM_PROPERTY, "2");
		System.setProperty(RemoteWorldgenConfig.TIMEOUT_MILLIS_SYSTEM_PROPERTY, "500");
		System.setProperty(RemoteWorldgenConfig.CACHE_ENTRIES_SYSTEM_PROPERTY, "3");
		System.setProperty(RemoteWorldgenConfig.PREDICTION_ENABLED_SYSTEM_PROPERTY, "true");
		System.setProperty(RemoteWorldgenConfig.PREDICTION_INTERVAL_TICKS_SYSTEM_PROPERTY, "5");
		System.setProperty(RemoteWorldgenConfig.PREDICTION_LEAD_CHUNKS_SYSTEM_PROPERTY, "2");
		System.setProperty(RemoteWorldgenConfig.VALIDATION_SAMPLE_CELLS_SYSTEM_PROPERTY, "8");

		RemoteWorldgenConfig config = RemoteWorldgenConfig.current();

		assertTrue(config.enabled());
		assertEquals(RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW, config.seedDisclosureMode());
		assertTrue(config.remoteExecutionEnabled());
		assertEquals(2, config.maxInFlightJobs());
		assertEquals(Duration.ofMillis(500), config.jobTimeout());
		assertEquals(3, config.cacheEntries());
		assertTrue(config.predictionEnabled());
		assertEquals(5, config.predictionIntervalTicks());
		assertEquals(2, config.predictionLeadChunks());
		assertEquals(8, config.validationSampleCells());
	}

	@Test
	void remoteEnableAloneDoesNotAuthorizeSeedDisclosure() {
		System.setProperty(RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY, "true");

		RemoteWorldgenConfig config = RemoteWorldgenConfig.current();

		assertTrue(config.enabled());
		assertEquals(RemoteWorldgenConfig.SeedDisclosureMode.DENY, config.seedDisclosureMode());
		assertFalse(config.remoteExecutionEnabled());
	}

	@Test
	void rejectsUnknownSeedDisclosureMode() {
		System.setProperty(RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY, "encrypted");

		assertThrows(IllegalArgumentException.class, RemoteWorldgenConfig::current);
	}

	@Test
	void rejectsInvalidLimits() {
		assertThrows(IllegalArgumentException.class, () -> new RemoteWorldgenConfig(true, 0, Duration.ofSeconds(1)));
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteWorldgenConfig(true, RemoteWorldgenConfig.MAX_IN_FLIGHT_JOBS + 1, Duration.ofSeconds(1))
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteWorldgenConfig(true, 1, Duration.ofMillis(RemoteWorldgenConfig.MIN_TIMEOUT_MILLIS - 1))
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteWorldgenConfig(true, 1, Duration.ofSeconds(1), RemoteWorldgenConfig.MAX_CACHE_ENTRIES + 1)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteWorldgenConfig(true, 1, Duration.ofSeconds(1), 1, true, 0, 1)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteWorldgenConfig(true, 1, Duration.ofSeconds(1), 1, true, 20, RemoteWorldgenConfig.MAX_PREDICTION_LEAD_CHUNKS + 1)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteWorldgenConfig(true, 1, Duration.ofSeconds(1), 0, true, 20, 1)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteWorldgenConfig(true, 1, Duration.ofSeconds(1), 1, false, 20, 1, -1)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteWorldgenConfig(
				true,
				1,
				Duration.ofSeconds(1),
				1,
				false,
				20,
				1,
				RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS + 1
			)
		);
	}
}
