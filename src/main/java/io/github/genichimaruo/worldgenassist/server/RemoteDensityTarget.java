package io.github.genichimaruo.worldgenassist.server;

import java.util.UUID;

public interface RemoteDensityTarget {
	void worldgenAssist$installRemoteDensity(RemoteDensityField densityField);

	void worldgenAssist$clearRemoteDensity(UUID jobId);

	RemoteDensityField worldgenAssist$getRemoteDensity();
}
