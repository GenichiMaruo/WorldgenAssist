package io.github.genichimaruo.worldgenassist.client;

import net.fabricmc.api.ClientModInitializer;

import io.github.genichimaruo.worldgenassist.network.WorldgenPayloadTypes;
import io.github.genichimaruo.worldgenassist.common.SeededLeafFixtureConfig;

public final class WorldgenAssistClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		WorldgenPayloadTypes.register();
		if (SeededLeafFixtureConfig.clientEnabled()) {
			new SeededLeafFixtureClient().register();
		} else {
			new ClientWorldgenWorker().register();
		}
	}
}
