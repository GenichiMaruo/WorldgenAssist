package io.github.genichimaruo.worldgenassist.server;

import java.util.UUID;

public interface RemoteDensityTarget {
	void worldgenAssist$installRemoteDensity(RemoteDensityField densityField);

	void worldgenAssist$clearRemoteDensity(UUID jobId);

	RemoteDensityField worldgenAssist$getRemoteDensity();
	void worldgenAssist$installRemoteOpportunity(RemoteDensityOpportunity opportunity);
	RemoteDensityOpportunity worldgenAssist$takeRemoteOpportunity();
	void worldgenAssist$clearRemoteOpportunity(RemoteDensityOpportunity opportunity);
}
