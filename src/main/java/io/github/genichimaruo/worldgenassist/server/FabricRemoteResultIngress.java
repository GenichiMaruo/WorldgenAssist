package io.github.genichimaruo.worldgenassist.server;

import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;

/** Only fully decoded/reassembled terrain results bypass the play main-thread queue. */
public final class FabricRemoteResultIngress {
	private static final ConnectionScopedResultIngress<Connection> SESSIONS = new ConnectionScopedResultIngress<>();
	private FabricRemoteResultIngress() {}
	public static void bind(Connection connection, UUID owner, RemoteWorldgenManager manager) {
		SESSIONS.bind(connection, owner, manager::receiveResultFromNetwork);
	}
	public static void remove(Connection connection) { SESSIONS.remove(connection); }
	public static void clear() { SESSIONS.clear(); }
	public static boolean receive(Connection connection, Packet<?> packet) {
		if (!(packet instanceof ServerboundCustomPayloadPacket custom)
			|| !(custom.payload() instanceof TerrainJobResultPayload result)) return false;
		// Unregistered/obsolete connections cannot fall through to UUID-only handling.
		SESSIONS.receive(connection, result, System.nanoTime());
		return true;
	}
}
