package io.github.genichimaruo.worldgenassist.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobBatchPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

public final class FabricClientWorkerEvents {
	private FabricClientWorkerEvents() {}

	public static void register() {
		FabricClientChunkReceiptProbe.register();
		ClientWorldgenWorker worker = new ClientWorldgenWorker(new ClientWorkerTransport() {
			@Override public boolean canSendHello() { return ClientPlayNetworking.canSend(WorkerHelloPayload.TYPE); }
			@Override public void send(CustomPacketPayload payload) { ClientPlayNetworking.send(payload); }
			@Override public java.util.function.Consumer<CustomPacketPayload> captureSender() {
				var connection = net.minecraft.client.Minecraft.getInstance().getConnection().getConnection();
				return payload -> {
					if (connection.isConnected()) connection.send(ClientPlayNetworking.createServerboundPacket(payload));
				};
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(WorkerAcceptedPayload.TYPE, (payload, context) -> worker.onAccepted(payload));
		ClientPlayNetworking.registerGlobalReceiver(TerrainJobRequestPayload.TYPE,
			(payload, context) -> worker.handleRequest(context.client(), payload.job()));
		ClientPlayNetworking.registerGlobalReceiver(TerrainJobBatchPayload.TYPE,
			(payload, context) -> payload.jobs().forEach(job -> worker.handleRequest(context.client(), job)));
		ClientPlayNetworking.registerGlobalReceiver(TerrainJobCancelPayload.TYPE, (payload, context) -> worker.cancel(payload));
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> worker.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> worker.onDisconnect());
	}
}
