package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NoiseStageBackendConfigTest {
	@Test
	void parsesModesAndBoundsWorkerThreads() {
		assertEquals(NoiseStageBackendConfig.Mode.VANILLA, NoiseStageBackendConfig.parseMode(null));
		assertEquals(NoiseStageBackendConfig.Mode.VANILLA, NoiseStageBackendConfig.parseMode("vanilla"));
		assertEquals(NoiseStageBackendConfig.Mode.DELEGATE, NoiseStageBackendConfig.parseMode(" DELEGATE "));
		assertEquals(NoiseStageBackendConfig.Mode.LOCAL, NoiseStageBackendConfig.parseMode(" LOCAL "));
		assertEquals(NoiseStageBackendConfig.Mode.VANILLA, NoiseStageBackendConfig.parseMode("unsupported"));

		assertEquals(1, NoiseStageBackendConfig.parseWorkerThreads(null));
		assertEquals(1, NoiseStageBackendConfig.parseWorkerThreads("1"));
		assertEquals(64, NoiseStageBackendConfig.parseWorkerThreads("64"));
		assertEquals(1, NoiseStageBackendConfig.parseWorkerThreads("0"));
		assertEquals(1, NoiseStageBackendConfig.parseWorkerThreads("65"));
		assertEquals(1, NoiseStageBackendConfig.parseWorkerThreads("not-a-number"));

		assertEquals(1, NoiseStageBackendConfig.parseQueuedTasksPerWorker(null));
		assertEquals(0, NoiseStageBackendConfig.parseQueuedTasksPerWorker("0"));
		assertEquals(16, NoiseStageBackendConfig.parseQueuedTasksPerWorker("16"));
		assertEquals(1, NoiseStageBackendConfig.parseQueuedTasksPerWorker("-1"));
		assertEquals(1, NoiseStageBackendConfig.parseQueuedTasksPerWorker("17"));
		assertEquals(1, NoiseStageBackendConfig.parseQueuedTasksPerWorker("not-a-number"));
	}
}
