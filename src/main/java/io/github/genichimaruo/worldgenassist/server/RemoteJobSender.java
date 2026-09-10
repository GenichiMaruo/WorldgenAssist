package io.github.genichimaruo.worldgenassist.server;

import java.util.UUID;

import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;

public interface RemoteJobSender {
	boolean canSend(UUID ownerId);

	void sendJob(UUID ownerId, TerrainJobRequestPayload payload);

	void sendCancel(UUID ownerId, TerrainJobCancelPayload payload);
}
