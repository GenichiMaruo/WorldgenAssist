package io.github.genichimaruo.worldgenassist;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import io.github.genichimaruo.worldgenassist.network.FabricPayloadRegistration;
import io.github.genichimaruo.worldgenassist.server.FabricFingerprintLoggerEvents;
import io.github.genichimaruo.worldgenassist.server.FabricRemoteJobSender;
import io.github.genichimaruo.worldgenassist.server.FabricRemoteWorldgenEvents;
import io.github.genichimaruo.worldgenassist.server.FabricServerSettingsEvents;
import io.github.genichimaruo.worldgenassist.server.FabricTickBenchmarkEvents;
import io.github.genichimaruo.worldgenassist.server.LocalWorldgenTaskBackend;
import io.github.genichimaruo.worldgenassist.server.NoiseStageBackendConfig;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenConfig;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;

public final class FabricWorldgenAssist implements ModInitializer, WorldgenLoaderHooks {
	@Override public void onInitialize() {
		FabricLoader loader = FabricLoader.getInstance();
		WorldgenPlatform.install(loader.getConfigDir(),
			loader.getModContainer(WorldgenAssist.MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString(),
			loader.isDevelopmentEnvironment());
		WorldgenAssist.initialize(this);
	}
	@Override public void registerPayloads() { FabricPayloadRegistration.register(); }
	@Override public void registerSettings() { FabricServerSettingsEvents.register(); }
	@Override public void registerRemote(RemoteWorldgenConfig config) {
		RemoteWorldgenManager.register(config, new FabricRemoteJobSender(RemoteWorldgenManager::activeServer),
			FabricRemoteWorldgenEvents::register);
	}
	@Override public void registerLocalBackend(LocalWorldgenTaskBackend backend) {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> backend.start());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> backend.close());
	}
	@Override public void registerTickBenchmark(NoiseStageBackendConfig config) {
		FabricTickBenchmarkEvents.register(config);
	}
	@Override public void registerFingerprintLogger() { FabricFingerprintLoggerEvents.register(); }
}
