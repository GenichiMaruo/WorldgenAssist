package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.Executor;

import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.Test;

class VanillaDelegatingWorldgenTaskBackendTest {
	@Test
	void preservesTheExactVanillaExecutorIdentity() {
		VanillaDelegatingWorldgenTaskBackend backend = VanillaDelegatingWorldgenTaskBackend.instance();
		Executor vanillaExecutor = Runnable::run;

		assertEquals("delegate", backend.id());
		assertEquals("minecraft_shared_fork_join_async", backend.schedulerId());
		assertSame(vanillaExecutor, backend.executorFor("noise", new ChunkPos(3, -4), vanillaExecutor));
	}
}
