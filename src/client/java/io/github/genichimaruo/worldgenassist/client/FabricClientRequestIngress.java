package io.github.genichimaruo.worldgenassist.client;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import io.github.genichimaruo.worldgenassist.network.*;

/** The network thread only admits bounded jobs against a main-thread snapshot. */
public final class FabricClientRequestIngress {
	private static final ConcurrentHashMap<Connection, ClientWorldgenWorker> WORKERS = new ConcurrentHashMap<>();
	private FabricClientRequestIngress() {}
	public static void bind(Connection connection, ClientWorldgenWorker worker) { WORKERS.put(connection, worker); }
	public static void remove(Connection connection) {
		var worker = WORKERS.remove(connection);
		if (worker != null) worker.closeNetworkContext(connection);
	}
	public static boolean receive(Connection connection, Packet<?> packet) {
		var worker = WORKERS.get(connection);
		if (worker == null) return false;
		if (packet instanceof ClientboundRespawnPacket) {
			// Invalidate before PacketUtils schedules the dimension transition.
			worker.suspendNetworkContext(connection);
			return false;
		}
		if (!(packet instanceof ClientboundCustomPayloadPacket custom)) return false;
		long receivedNanos = System.nanoTime();
		if (custom.payload() instanceof TerrainJobRequestPayload request) {
			return worker.handleRequestFromNetwork(connection, request.job(), receivedNanos);
		}
		if (custom.payload() instanceof TerrainJobBatchPayload batch) {
			// One worker lock covers the complete batch, preventing partial fallback/replay.
			synchronized (worker) {
				if (!worker.handleRequestFromNetwork(connection, batch.jobs().getFirst(), receivedNanos)) return false;
				for (int i = 1; i < batch.jobs().size(); i++) worker.handleRequestFromNetwork(connection, batch.jobs().get(i), receivedNanos);
				return true;
			}
		}
		return custom.payload() instanceof TerrainJobCancelPayload cancel && worker.cancelFromNetwork(connection, cancel);
	}
}
