package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.Executor;

import net.minecraft.world.level.ChunkPos;

public final class VanillaDelegatingWorldgenTaskBackend implements WorldgenTaskBackend {
	public static final String SCHEDULER_ID = "minecraft_shared_fork_join_async";
	private static final VanillaDelegatingWorldgenTaskBackend INSTANCE = new VanillaDelegatingWorldgenTaskBackend();

	private VanillaDelegatingWorldgenTaskBackend() {
	}

	public static VanillaDelegatingWorldgenTaskBackend instance() {
		return INSTANCE;
	}

	@Override
	public String id() {
		return "delegate";
	}

	public String schedulerId() {
		return SCHEDULER_ID;
	}

	@Override
	public Executor executorFor(String stage, ChunkPos chunkPos, Executor fallbackExecutor) {
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(chunkPos, "chunkPos");
		return Objects.requireNonNull(fallbackExecutor, "fallbackExecutor");
	}

	@Override
	public void close() {
	}
}
