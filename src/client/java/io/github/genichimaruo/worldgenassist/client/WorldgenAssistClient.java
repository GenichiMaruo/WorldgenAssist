package io.github.genichimaruo.worldgenassist.client;

import net.fabricmc.api.ClientModInitializer;

import io.github.genichimaruo.worldgenassist.network.WorldgenPayloadTypes;

public final class WorldgenAssistClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		WorldgenPayloadTypes.register();
		WorldgenSettingsScreen.register();
		SettingsScreenSmoke.registerIfEnabled();
		new ClientWorldgenWorker().register();
	}
}
