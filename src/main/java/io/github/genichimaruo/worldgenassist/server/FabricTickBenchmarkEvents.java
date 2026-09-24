package io.github.genichimaruo.worldgenassist.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class FabricTickBenchmarkEvents {
	private FabricTickBenchmarkEvents() {}
	public static void register(NoiseStageBackendConfig config) {
		ServerTickBenchmarkLogger benchmark = ServerTickBenchmarkLogger.create(config);
		ServerLifecycleEvents.SERVER_STARTING.register(server -> benchmark.reset());
		ServerTickEvents.START_SERVER_TICK.register(server -> benchmark.onStartTick());
		ServerTickEvents.END_SERVER_TICK.register(server -> benchmark.onEndTick());
	}
}
