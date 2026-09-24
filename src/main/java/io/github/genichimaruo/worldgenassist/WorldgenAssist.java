package io.github.genichimaruo.worldgenassist;

import io.github.genichimaruo.worldgenassist.server.LocalWorldgenTaskBackend;
import io.github.genichimaruo.worldgenassist.server.NoiseStageDigest;
import io.github.genichimaruo.worldgenassist.server.NoiseStageDigestLogger;
import io.github.genichimaruo.worldgenassist.server.NoiseStageBackendConfig;
import io.github.genichimaruo.worldgenassist.server.NoiseTaskBenchmarkLogger;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenConfig;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import io.github.genichimaruo.worldgenassist.server.ServerTickBenchmarkLogger;
import io.github.genichimaruo.worldgenassist.server.VanillaDelegatingWorldgenTaskBackend;
import io.github.genichimaruo.worldgenassist.server.WorldgenContextFingerprintFactory;
import io.github.genichimaruo.worldgenassist.server.WorldgenContextFingerprintLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldgenAssist {
	public static final String MOD_ID = "worldgen_assist";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private WorldgenAssist() {}

	public static void initialize(WorldgenLoaderHooks hooks) {
		if (unsupportedFixtureFlag("worldgen_assist.seeded_leaf.public_fixture", "WORLDGEN_ASSIST_SEEDED_LEAF_PUBLIC_FIXTURE")
			|| unsupportedFixtureFlag("worldgen_assist.client.seeded_leaf.public_fixture", "WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_PUBLIC_FIXTURE")) {
			throw new IllegalStateException("The public-seed transcript fixture is not available on Minecraft 26.3");
		}
		LOGGER.info("[CAWG] initialized");
		hooks.registerPayloads();
		RemoteWorldgenConfig remoteConfig = RemoteWorldgenConfig.current(io.github.genichimaruo.worldgenassist.server.ServerSettingsStore.load());
		hooks.registerSettings();
		hooks.registerRemote(remoteConfig);
		if (remoteConfig.remoteExecutionEnabled()) {
			LOGGER.warn(
				"[CAWG] remote.enabled seed_disclosure={} max_in_flight={} timeout_ms={} cache_entries={} prediction={} prediction_interval_ticks={} prediction_lead_chunks={} validation_sample_cells={} property={} environment_variable={}",
				remoteConfig.seedDisclosureMode(),
				remoteConfig.maxInFlightJobs(),
				remoteConfig.jobTimeout().toMillis(),
				remoteConfig.cacheEntries(),
				remoteConfig.predictionEnabled(),
				remoteConfig.predictionIntervalTicks(),
				remoteConfig.predictionLeadChunks(),
				remoteConfig.validationSampleCells(),
				RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY,
				RemoteWorldgenConfig.ENABLED_ENVIRONMENT_VARIABLE
			);
		} else if (remoteConfig.enabled()) {
			LOGGER.warn(
				"[CAWG] remote.blocked reason=seed_disclosure_not_authorized mode={} property={} environment_variable={} expected=trusted_raw",
				remoteConfig.seedDisclosureMode(),
				RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY,
				RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_ENVIRONMENT_VARIABLE
			);
		}
		NoiseStageBackendConfig backendConfig = NoiseStageBackendConfig.current();
		if (backendConfig.mode() == NoiseStageBackendConfig.Mode.LOCAL) {
			LocalWorldgenTaskBackend backend = LocalWorldgenTaskBackend.instance();
			hooks.registerLocalBackend(backend);
			LOGGER.info(
				"[CAWG] backend.enabled stage=noise mode={} scheduler={} workers={} queue_per_worker={} queue_capacity={} property={} environment_variable={} workers_property={} workers_environment_variable={} queue_property={} queue_environment_variable={}",
				backend.id(),
				backend.schedulerId(),
				backendConfig.workerThreads(),
				backendConfig.queuedTasksPerWorker(),
				backend.queueCapacity(),
				NoiseStageBackendConfig.MODE_SYSTEM_PROPERTY,
				NoiseStageBackendConfig.MODE_ENVIRONMENT_VARIABLE,
				NoiseStageBackendConfig.WORKERS_SYSTEM_PROPERTY,
				NoiseStageBackendConfig.WORKERS_ENVIRONMENT_VARIABLE,
				NoiseStageBackendConfig.QUEUE_PER_WORKER_SYSTEM_PROPERTY,
				NoiseStageBackendConfig.QUEUE_PER_WORKER_ENVIRONMENT_VARIABLE
			);
		} else if (backendConfig.mode() == NoiseStageBackendConfig.Mode.DELEGATE) {
			VanillaDelegatingWorldgenTaskBackend backend = VanillaDelegatingWorldgenTaskBackend.instance();
			LOGGER.info(
				"[CAWG] backend.enabled stage=noise mode={} scheduler={} property={} environment_variable={}",
				backend.id(),
				backend.schedulerId(),
				NoiseStageBackendConfig.MODE_SYSTEM_PROPERTY,
				NoiseStageBackendConfig.MODE_ENVIRONMENT_VARIABLE
			);
		}
		if (NoiseStageDigestLogger.isEnabled()) {
			LOGGER.warn(
				"[CAWG] stage.digest_enabled stage=noise format={} property={} environment_variable={}",
				NoiseStageDigest.FORMAT_VERSION,
				NoiseStageDigestLogger.SYSTEM_PROPERTY,
				NoiseStageDigestLogger.ENVIRONMENT_VARIABLE
			);
		}
		if (NoiseTaskBenchmarkLogger.isEnabled()) {
			LOGGER.warn(
				"[CAWG] benchmark.enabled stage=noise property={} environment_variable={}",
				NoiseTaskBenchmarkLogger.SYSTEM_PROPERTY,
				NoiseTaskBenchmarkLogger.ENVIRONMENT_VARIABLE
			);
			if (NoiseStageDigestLogger.isEnabled()) {
				LOGGER.warn("[CAWG] benchmark.invalid stage=noise reason=digest_enabled");
			}
		}
		if (ServerTickBenchmarkLogger.isEnabled()) {
			hooks.registerTickBenchmark(backendConfig);
			LOGGER.warn(
				"[CAWG] tick_benchmark.enabled budget_ms={} property={} environment_variable={}",
				ServerTickBenchmarkLogger.TICK_BUDGET_NANOS / 1_000_000.0,
				ServerTickBenchmarkLogger.SYSTEM_PROPERTY,
				ServerTickBenchmarkLogger.ENVIRONMENT_VARIABLE
			);
		}
		if (WorldgenContextFingerprintLogger.isEnabled()) {
			hooks.registerFingerprintLogger();
			LOGGER.warn(
				"[CAWG] context.fingerprint_enabled format={} property={} environment_variable={}",
				WorldgenContextFingerprintFactory.FORMAT_VERSION,
				WorldgenContextFingerprintLogger.SYSTEM_PROPERTY,
				WorldgenContextFingerprintLogger.ENVIRONMENT_VARIABLE
			);
		}
	}

	private static boolean unsupportedFixtureFlag(String property, String environment) {
		String value = System.getProperty(property);
		if (value == null) { value = System.getenv(environment); }
		if (value == null || value.equalsIgnoreCase("false")) { return false; }
		if (value.equalsIgnoreCase("true")) { return true; }
		throw new IllegalArgumentException("Expected true/false for " + property);
	}
}
