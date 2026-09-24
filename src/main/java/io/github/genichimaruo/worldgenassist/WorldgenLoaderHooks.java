package io.github.genichimaruo.worldgenassist;

import io.github.genichimaruo.worldgenassist.server.LocalWorldgenTaskBackend;
import io.github.genichimaruo.worldgenassist.server.NoiseStageBackendConfig;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenConfig;

/** Loader lifecycle and networking registration around the shared core. */
public interface WorldgenLoaderHooks {
	void registerPayloads();
	void registerSettings();
	void registerRemote(RemoteWorldgenConfig config);
	void registerLocalBackend(LocalWorldgenTaskBackend backend);
	void registerTickBenchmark(NoiseStageBackendConfig config);
	void registerFingerprintLogger();
}
