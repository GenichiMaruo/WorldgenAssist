package io.github.genichimaruo.worldgenassist.client;

import net.fabricmc.api.ClientModInitializer;

import io.github.genichimaruo.worldgenassist.network.FabricPayloadRegistration;

public final class WorldgenAssistClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FabricPayloadRegistration.register();
		FabricSettingsScreenEvents.register();
		SettingsScreenSmoke smoke = SettingsScreenSmoke.createIfEnabled();
		if (smoke != null) net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(smoke::tick);
		FabricClientWorkerEvents.register();
	}
}
