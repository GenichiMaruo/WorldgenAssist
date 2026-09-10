package io.github.genichimaruo.worldgenassist.server;

import java.util.concurrent.Executor;

import net.minecraft.world.level.ChunkPos;

public interface WorldgenTaskBackend extends AutoCloseable {
	String id();

	Executor executorFor(String stage, ChunkPos chunkPos, Executor fallbackExecutor);

	@Override
	void close();
}
