package io.github.genichimaruo.worldgenassist.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import io.github.genichimaruo.worldgenassist.network.SettingsPayload;

public final class FabricServerSettingsEvents {
	private FabricServerSettingsEvents() {}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE, (payload, context) ->
			ServerSettingsMenu.handleRequest(context.server(), context.player(), payload,
				response -> ServerPlayNetworking.send(context.player(), response)));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			server.execute(() -> ServerSettingsMenu.onDisconnect(handler.player.getUUID())));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerSettingsMenu.onServerStopped());
	}
}
