package io.github.genichimaruo.worldgenassist.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobBatchPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;

/** Native payload APIs admit against a main-thread snapshot, without reading a live world. */
public final class NativeClientRequestIngress {
	private static final boolean ENABLED = Boolean.parseBoolean(
		System.getProperty("worldgen_assist.native.client_request_ingress", "false"));
	private static final ClientConnectionIngress<Connection, CustomPacketPayload> SESSIONS = new ClientConnectionIngress<>();
	private NativeClientRequestIngress() {}
	public static boolean enabled() { return ENABLED; }

	/** MAIN only: acceptance or the original fallback prepares the private worker context. */
	public static void prepare(Minecraft client, ClientWorldgenWorker worker) {
		if (!ENABLED || client.getConnection() == null) return;
		var connection = client.getConnection().getConnection();
		if (!connection.isConnected() || !worker.prepareRequestContext(client) || SESSIONS.matches(connection)) return;
		SESSIONS.bind(connection, new ClientConnectionIngress.Receiver<>() {
			@Override public boolean receive(CustomPacketPayload payload, long received, java.util.function.BooleanSupplier current) {
				synchronized (worker) {
					if (!current.getAsBoolean()) return true; // Consume obsolete work, never replay on a replacement.
					if (payload instanceof TerrainJobRequestPayload request)
						return worker.handleRequestFromNetwork(connection, request.job(), received);
					if (payload instanceof TerrainJobBatchPayload batch) {
						if (!worker.handleRequestFromNetwork(connection, batch.jobs().getFirst(), received)) return false;
						for (int i = 1; i < batch.jobs().size(); i++)
							worker.handleRequestFromNetwork(connection, batch.jobs().get(i), received);
						return true;
					}
					return payload instanceof TerrainJobCancelPayload cancel && worker.cancelFromNetwork(connection, cancel);
				}
			}
			@Override public void suspend() {
				worker.suspendNetworkContext(connection);
				io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info("[CAWG] worker.native_request_suspended");
			}
			@Override public boolean resume() {
				// The respawn TAIL hook calls this after the original MAIN transition.
				if (client.getConnection() == null || client.getConnection().getConnection() != connection || !connection.isConnected()) return false;
				worker.resumeNetworkContext(connection);
				boolean ready = worker.prepareRequestContext(client);
				if (ready) io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info(
					"[CAWG] worker.native_request_resumed dimension={}", client.level.dimension().identifier());
				return ready;
			}
			@Override public void close() {
				worker.closeNetworkContext(connection);
				io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info("[CAWG] worker.native_request_revoked");
			}
		});
		io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info(
			"[CAWG] worker.native_request_bound dimension={}", client.level.dimension().identifier());
	}
	public static boolean receive(Connection connection, CustomPacketPayload payload, long receivedNanos) {
		return ENABLED && SESSIONS.receive(connection, payload, receivedNanos);
	}
	public static void suspend(Connection connection) { SESSIONS.suspend(connection); }
	public static void remove(Connection connection) { SESSIONS.remove(connection); }
	public static void clear() { SESSIONS.clear(); }
	/** MAIN only, after original handleRespawn. */
	public static void resume(Minecraft client) {
		if (client.getConnection() != null) SESSIONS.resume(client.getConnection().getConnection());
	}
	/** MAIN fallback keeps the immutable native receive connection attribution. */
	public static boolean isCurrent(Minecraft client, Connection connection) {
		return connection.isConnected() && client.getConnection() != null && client.getConnection().getConnection() == connection;
	}
}
