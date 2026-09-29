package io.github.genichimaruo.worldgenassist.server;

import java.util.UUID;

import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;

public interface RemoteJobSender {
	boolean canSend(UUID ownerId);

	void sendJob(UUID ownerId, TerrainJobRequestPayload payload);
	/** Loaders without the optional batch channel retain individual requests. */
	default void sendJobs(UUID ownerId, java.util.List<io.github.genichimaruo.worldgenassist.common.TerrainDensityJob> jobs) {
		for (var job : jobs) sendJob(ownerId, new TerrainJobRequestPayload(job));
	}

	void sendCancel(UUID ownerId, TerrainJobCancelPayload payload);
}
