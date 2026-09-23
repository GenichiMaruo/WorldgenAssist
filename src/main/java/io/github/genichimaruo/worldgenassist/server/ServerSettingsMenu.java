package io.github.genichimaruo.worldgenassist.server;

import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** All policy access is checked on the authoritative server thread. Saves need JVM restart. */
public final class ServerSettingsMenu {
	private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
	private static final ServerSettingsDecisionService DECISIONS = new ServerSettingsDecisionService(
		ServerSettingsStore::load,
		ServerSettingsStore::save
	);
	private ServerSettingsMenu() {}
	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE, (payload, context) -> context.server().execute(() -> {
			var player = context.player();
			if (context.server().getPlayerList().getPlayer(player.getUUID()) != player) return;
			if (payload.action() != SettingsPayload.READ && payload.action() != SettingsPayload.SAVE) return;
			SettingsPayload limited = rateLimit(player.getUUID(), System.nanoTime(), payload);
			if (limited != null) { ServerPlayNetworking.send(player, limited); return; }
			SettingsPayload response = DECISIONS.decide(player.createCommandSourceStack().permissions(), payload);
			if (response != null) ServerPlayNetworking.send(player, response);
		}));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> LAST_REQUEST.remove(handler.player.getUUID())));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> { LAST_REQUEST.clear(); DECISIONS.reset(); });
	}
	static boolean mayManage(net.minecraft.server.permissions.PermissionSet permissions) {
		return ServerSettingsDecisionService.mayManage(permissions);
	}
	static SettingsPayload rateLimit(UUID owner, long now, SettingsPayload request) {
		Long previous = LAST_REQUEST.get(owner);
		if (previous != null && now - previous < 250_000_000L) {
			return new SettingsPayload(SettingsPayload.RATE_LIMITED, 0, request.requestId(), RemoteWorldgenConfig.defaults());
		}
		LAST_REQUEST.put(owner, now);
		return null;
	}
}
