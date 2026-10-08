package io.github.genichimaruo.worldgenassist.forge;

import java.util.UUID;

import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobBatchPayload;
import io.github.genichimaruo.worldgenassist.server.RemoteJobSender;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class ForgeRemoteJobSender implements RemoteJobSender {
    private static final boolean REQUEST_BATCHING = Boolean.parseBoolean(
        System.getProperty("worldgen_assist.native.request_batching", "false"));
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
    @Override public void sendJobs(UUID ownerId, java.util.List<io.github.genichimaruo.worldgenassist.common.TerrainDensityJob> jobs) {
        if (!REQUEST_BATCHING || jobs.size() <= 1) {
            RemoteJobSender.super.sendJobs(ownerId, jobs);
            return;
        }
        ServerPlayer player = player(ownerId);
        if (player == null || !canSend(ownerId)) throw new IllegalStateException("Worker cannot receive job: " + ownerId);
        ForgeNetwork.send(new TerrainJobBatchPayload(jobs), player.connection.getConnection());
        io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info(
            "[CAWG] jobs.batch_sent owner={} count={}", ownerId, jobs.size());
    }
    @Override public void sendCancel(UUID ownerId, TerrainJobCancelPayload payload) {
        ServerPlayer player = player(ownerId);
        if (player != null && canSend(ownerId)) ForgeNetwork.send(payload, player.connection.getConnection());
    }
}
