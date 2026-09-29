package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobBatchPayload;

public final class FabricRemoteJobSender implements RemoteJobSender {
	private final Supplier<MinecraftServer> server;

	public FabricRemoteJobSender(Supplier<MinecraftServer> server) {
		this.server = Objects.requireNonNull(server, "server");
	}

	@Override
	public boolean canSend(UUID ownerId) {
		ServerPlayer player = player(ownerId);
		return player != null && ServerPlayNetworking.canSend(player, TerrainJobRequestPayload.TYPE);
	}

	@Override
	public void sendJob(UUID ownerId, TerrainJobRequestPayload payload) {
		ServerPlayer player = requirePlayer(ownerId);
		if (!ServerPlayNetworking.canSend(player, TerrainJobRequestPayload.TYPE)) {
			throw new IllegalStateException("Remote worker cannot receive terrain jobs: " + ownerId);
		}
		ServerPlayNetworking.send(player, payload);
	}

	@Override
	public void sendJobs(UUID ownerId, java.util.List<io.github.genichimaruo.worldgenassist.common.TerrainDensityJob> jobs) {
		ServerPlayer player = requirePlayer(ownerId);
		if (jobs.size() > 1 && ServerPlayNetworking.canSend(player, TerrainJobBatchPayload.TYPE)) {
			ServerPlayNetworking.send(player, new TerrainJobBatchPayload(jobs));
			io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info("[CAWG] jobs.batch_sent owner={} count={}", ownerId, jobs.size());
		} else RemoteJobSender.super.sendJobs(ownerId, jobs);
	}

	@Override
	public void sendCancel(UUID ownerId, TerrainJobCancelPayload payload) {
		ServerPlayer player = player(ownerId);
		if (player != null && ServerPlayNetworking.canSend(player, TerrainJobCancelPayload.TYPE)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private ServerPlayer requirePlayer(UUID ownerId) {
		ServerPlayer player = player(ownerId);
		if (player == null) {
			throw new IllegalStateException("Remote worker is no longer connected: " + ownerId);
		}
		return player;
	}

	private ServerPlayer player(UUID ownerId) {
		MinecraftServer currentServer = server.get();
		return currentServer == null ? null : currentServer.getPlayerList().getPlayer(ownerId);
	}
}
