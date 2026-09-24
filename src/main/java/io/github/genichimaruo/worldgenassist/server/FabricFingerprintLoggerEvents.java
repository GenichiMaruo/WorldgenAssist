package io.github.genichimaruo.worldgenassist.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class FabricFingerprintLoggerEvents {
	private FabricFingerprintLoggerEvents() {}
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(WorldgenContextFingerprintLogger::log);
	}
}
