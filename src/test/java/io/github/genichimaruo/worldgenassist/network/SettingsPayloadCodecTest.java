package io.github.genichimaruo.worldgenassist.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.time.Duration;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenConfig;
import org.junit.jupiter.api.Test;

class SettingsPayloadCodecTest {
	@Test
	void roundTripsACompleteSavedSettingsPayload() {
		RemoteWorldgenConfig config = new RemoteWorldgenConfig(
			true,
			RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW,
			64,
			Duration.ofMillis(RemoteWorldgenConfig.MAX_TIMEOUT_MILLIS),
			RemoteWorldgenConfig.MAX_CACHE_ENTRIES,
			true,
			RemoteWorldgenConfig.MAX_PREDICTION_INTERVAL_TICKS,
			RemoteWorldgenConfig.MAX_PREDICTION_LEAD_CHUNKS,
			RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS
		);
		SettingsPayload expected = new SettingsPayload(SettingsPayload.SAVED, 17, 42L, config);

		assertEquals(expected, roundTrip(expected));
		assertEquals(new SettingsPayload(SettingsPayload.RATE_LIMITED, 0, 43L, RemoteWorldgenConfig.defaults()),
			roundTrip(new SettingsPayload(SettingsPayload.RATE_LIMITED, 0, 43L, RemoteWorldgenConfig.defaults())));
	}

	@Test
	void rejectsInvalidActionsRevisionsAndRequestIdsAtThePayloadBoundary() {
		RemoteWorldgenConfig defaults = RemoteWorldgenConfig.defaults();
		assertThrows(IllegalArgumentException.class, () -> new SettingsPayload(-1, 0, 0L, defaults));
		assertThrows(IllegalArgumentException.class, () -> new SettingsPayload(SettingsPayload.RATE_LIMITED + 1, 0, 0L, defaults));
		assertThrows(IllegalArgumentException.class, () -> new SettingsPayload(SettingsPayload.READ, -1, 0L, defaults));
		assertThrows(IllegalArgumentException.class, () -> new SettingsPayload(SettingsPayload.READ, 0, -1L, defaults));

		assertMalformed(-1, 0, 0L, defaults);
		assertMalformed(SettingsPayload.READ, -1, 0L, defaults);
		assertMalformed(SettingsPayload.READ, 0, -1L, defaults);
	}

	@Test
	void rejectsOutOfRangeSettingsBeforeTheyCanReachTheMenu() {
		RemoteWorldgenConfig defaults = RemoteWorldgenConfig.defaults();
		assertMalformed(SettingsPayload.SAVE, 0, 0L, false, false, 0, defaults.jobTimeout().toMillis(), defaults.cacheEntries(),
			false, defaults.predictionIntervalTicks(), defaults.predictionLeadChunks(), defaults.validationSampleCells());
		assertMalformed(SettingsPayload.SAVE, 0, 0L, false, false, defaults.maxInFlightJobs(),
			RemoteWorldgenConfig.MIN_TIMEOUT_MILLIS - 1, defaults.cacheEntries(), false,
			defaults.predictionIntervalTicks(), defaults.predictionLeadChunks(), defaults.validationSampleCells());
		assertMalformed(SettingsPayload.SAVE, 0, 0L, false, false, defaults.maxInFlightJobs(), defaults.jobTimeout().toMillis(),
			defaults.cacheEntries(), false, defaults.predictionIntervalTicks(), RemoteWorldgenConfig.MAX_PREDICTION_LEAD_CHUNKS + 1,
			defaults.validationSampleCells());
		assertMalformed(SettingsPayload.SAVE, 0, 0L, false, false, defaults.maxInFlightJobs(), defaults.jobTimeout().toMillis(),
			defaults.cacheEntries(), false, defaults.predictionIntervalTicks(), defaults.predictionLeadChunks(),
			RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS + 1);
	}

	private static SettingsPayload roundTrip(SettingsPayload expected) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SettingsPayload.CODEC.encode(buffer, expected);
			SettingsPayload decoded = SettingsPayload.CODEC.decode(buffer);
			assertEquals(0, buffer.readableBytes());
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private static void assertMalformed(int action, int revision, long requestId, RemoteWorldgenConfig config) {
		assertMalformed(action, revision, requestId, config.enabled(), config.seedDisclosureMode() == RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW,
			config.maxInFlightJobs(), config.jobTimeout().toMillis(), config.cacheEntries(), config.predictionEnabled(),
			config.predictionIntervalTicks(), config.predictionLeadChunks(), config.validationSampleCells());
	}

	private static void assertMalformed(
		int action,
		int revision,
		long requestId,
		boolean enabled,
		boolean trustedRaw,
		int maxInFlight,
		long timeoutMillis,
		int cacheEntries,
		boolean prediction,
		int predictionInterval,
		int predictionLead,
		int validationCells
	) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			buffer.writeVarInt(action);
			buffer.writeVarInt(revision);
			buffer.writeVarLong(requestId);
			buffer.writeBoolean(enabled);
			buffer.writeBoolean(trustedRaw);
			buffer.writeVarInt(maxInFlight);
			buffer.writeVarLong(timeoutMillis);
			buffer.writeVarInt(cacheEntries);
			buffer.writeBoolean(prediction);
			buffer.writeVarInt(predictionInterval);
			buffer.writeVarInt(predictionLead);
			buffer.writeVarInt(validationCells);
			assertThrows(IllegalArgumentException.class, () -> SettingsPayload.CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}
}
