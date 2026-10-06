package io.github.genichimaruo.worldgenassist.server;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

/** Fabric registration only; all owner and lifecycle decisions stay in the shared manager. */
public final class FabricRemoteWorldgenEvents {
	private FabricRemoteWorldgenEvents() {}

	public static void register(RemoteWorldgenManager manager) {
		ServerPlayNetworking.registerGlobalReceiver(WorkerHelloPayload.TYPE, (payload, context) -> {
			var connection = context.packetContext().orElseThrow(net.fabricmc.fabric.api.networking.v1.context.PacketContext.CONNECTION);
			var response = manager.handleHello(context.player().getUUID(), payload);
			if (response.accepted()) FabricRemoteResultIngress.bind(connection, context.player().getUUID(), manager);
			else FabricRemoteResultIngress.remove(connection);
			context.responseSender().sendPacket(response);
		});
		ServerPlayNetworking.registerGlobalReceiver(TerrainJobResultPayload.TYPE, (payload, context) ->
			manager.handleResult(context.player().getUUID(), payload));
		ServerPlayNetworking.registerGlobalReceiver(io.github.genichimaruo.worldgenassist.network.TerrainBiomeResultPayload.TYPE, (payload, context) ->
			manager.handleBiomeResult(context.player().getUUID(), payload));
		ServerPlayNetworking.registerGlobalReceiver(TerrainJobFailurePayload.TYPE, (payload, context) ->
			manager.handleFailure(context.player().getUUID(), payload));
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
			server.execute(() -> manager.onDisconnect(server, listener.player)));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
			manager.onDimensionChanged(player, origin.dimension(), destination.dimension()));
		ServerLifecycleEvents.SERVER_STARTING.register(manager::onServerStarting);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> { FabricRemoteResultIngress.clear(); manager.onServerStopping(); });
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> manager.onServerStopped());
		ServerTickEvents.START_SERVER_TICK.register(manager::onStartServerTick);
		ServerTickEvents.END_SERVER_TICK.register(manager::onEndServerTick);
	}
}
