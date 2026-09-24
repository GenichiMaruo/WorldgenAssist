package io.github.genichimaruo.worldgenassist.forge;

import java.util.UUID;

import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.server.RemoteJobSender;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class ForgeRemoteJobSender implements RemoteJobSender {
    private static ServerPlayer player(UUID id) {
        MinecraftServer server = RemoteWorldgenManager.activeServer();
        return server == null ? null : server.getPlayerList().getPlayer(id);
    }
    @Override public boolean canSend(UUID ownerId) {
        ServerPlayer player = player(ownerId);
        return player != null && ForgeNetwork.canSend(player.connection.getConnection());
    }
    @Override public void sendJob(UUID ownerId, TerrainJobRequestPayload payload) {
        ServerPlayer player = player(ownerId);
        if (player == null || !canSend(ownerId)) throw new IllegalStateException("Worker cannot receive job: " + ownerId);
        ForgeNetwork.send(payload, player.connection.getConnection());
    }
    @Override public void sendCancel(UUID ownerId, TerrainJobCancelPayload payload) {
        ServerPlayer player = player(ownerId);
        if (player != null && canSend(ownerId)) ForgeNetwork.send(payload, player.connection.getConnection());
    }
}
