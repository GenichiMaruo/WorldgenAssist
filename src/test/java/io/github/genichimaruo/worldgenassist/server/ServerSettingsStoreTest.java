package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerSettingsStoreTest {
	@AfterEach
	void clearProperties() {
		System.clearProperty(RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY);
		System.clearProperty(RemoteWorldgenConfig.PREDICTION_ENABLED_SYSTEM_PROPERTY);
	}

	@Test
	void roundTripsEveryPersistedServerSetting() {
		RemoteWorldgenConfig expected = new RemoteWorldgenConfig(
			true,
			RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW,
			3,
			Duration.ofMillis(750),
			11,
			true,
			5,
			2,
			9
		);

		assertEquals(expected, ServerSettingsStore.decode(ServerSettingsStore.encode(expected)));
	}

	@Test
	void omittedDisclosureDefaultsToDenyEvenWhenSavedRemoteEnableIsTrue() {
		Properties saved = new Properties();
		saved.setProperty("enabled", "true");

		RemoteWorldgenConfig decoded = ServerSettingsStore.decode(saved);

		assertTrue(decoded.enabled());
		assertEquals(RemoteWorldgenConfig.SeedDisclosureMode.DENY, decoded.seedDisclosureMode());
		assertFalse(decoded.remoteExecutionEnabled());
	}

	@Test
	void rejectsUnknownInvalidAndOutOfRangePersistedValues() {
		Properties unknown = new Properties();
		unknown.setProperty("unexpected", "value");
		assertThrows(IllegalArgumentException.class, () -> ServerSettingsStore.decode(unknown));

		Properties invalidBoolean = new Properties();
		invalidBoolean.setProperty("prediction", "sometimes");
		assertThrows(IllegalArgumentException.class, () -> ServerSettingsStore.decode(invalidBoolean));

		Properties invalidDisclosure = new Properties();
		invalidDisclosure.setProperty("seed_disclosure", "private");
		assertThrows(IllegalArgumentException.class, () -> ServerSettingsStore.decode(invalidDisclosure));

		Properties tooManyJobs = new Properties();
		tooManyJobs.setProperty("max_in_flight", Integer.toString(RemoteWorldgenConfig.MAX_IN_FLIGHT_JOBS + 1));
		assertThrows(IllegalArgumentException.class, () -> ServerSettingsStore.decode(tooManyJobs));

		Properties invalidPredictionGeometry = new Properties();
		invalidPredictionGeometry.setProperty("prediction_lead_chunks", Integer.toString(RemoteWorldgenConfig.MAX_PREDICTION_LEAD_CHUNKS + 1));
		assertThrows(IllegalArgumentException.class, () -> ServerSettingsStore.decode(invalidPredictionGeometry));

		Properties tooManyValidationCells = new Properties();
		tooManyValidationCells.setProperty("validation_cells", Integer.toString(RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS + 1));
		assertThrows(IllegalArgumentException.class, () -> ServerSettingsStore.decode(tooManyValidationCells));
	}

	@Test
	void explicitCommandLineFalseOverridesSavedEnabledAndPredictionValues() {
		RemoteWorldgenConfig saved = new RemoteWorldgenConfig(
			true,
			RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW,
			2,
			Duration.ofMillis(500),
			4,
			true,
			5,
			2,
			6
		);
		System.setProperty(RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY, "false");
		System.setProperty(RemoteWorldgenConfig.PREDICTION_ENABLED_SYSTEM_PROPERTY, "false");

		RemoteWorldgenConfig effective = RemoteWorldgenConfig.current(saved);

		assertFalse(effective.enabled());
		assertFalse(effective.predictionEnabled());
		assertFalse(effective.remoteExecutionEnabled());
		assertEquals(RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW, effective.seedDisclosureMode());
	}
}
