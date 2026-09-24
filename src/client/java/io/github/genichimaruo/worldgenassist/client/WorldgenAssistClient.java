package io.github.genichimaruo.worldgenassist.client;

import net.fabricmc.api.ClientModInitializer;

import io.github.genichimaruo.worldgenassist.network.FabricPayloadRegistration;

public final class WorldgenAssistClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FabricPayloadRegistration.register();
		FabricSettingsScreenEvents.register();
		SettingsScreenSmoke.registerIfEnabled();
		FabricClientWorkerEvents.register();
	}
}
