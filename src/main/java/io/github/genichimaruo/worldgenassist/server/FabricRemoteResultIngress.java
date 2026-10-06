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
		SESSIONS.bind(connection, owner, manager::receiveResultFromNetwork, manager::receiveBiomeResultFromNetwork);
	}
	public static void remove(Connection connection) { SESSIONS.remove(connection); }
	public static void clear() { SESSIONS.clear(); }
	public static boolean receive(Connection connection, Packet<?> packet) {
		if (!(packet instanceof ServerboundCustomPayloadPacket custom)) return false;
		if (custom.payload() instanceof io.github.genichimaruo.worldgenassist.network.TerrainBiomeResultPayload biomes) {
			SESSIONS.receive(connection, biomes); return true;
		}
		if (!(custom.payload() instanceof TerrainJobResultPayload result)) return false;
		// Unregistered/obsolete connections cannot fall through to UUID-only handling.
		SESSIONS.receive(connection, result, System.nanoTime());
		return true;
	}
}
