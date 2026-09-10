package io.github.genichimaruo.worldgenassist.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.genichimaruo.worldgenassist.mixin.NoiseBasedChunkGeneratorInvoker;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.Test;

class MixinApplicationTest {
	@Test
	void verifiedChunkStatusTargetLoadsThroughFabricTestRuntime() throws ClassNotFoundException {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		Class<?> statusTarget = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatusTasks");
		Class<?> generatorTarget = Class.forName("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator");
		Class<?> chunkTarget = Class.forName("net.minecraft.world.level.chunk.ChunkAccess");
		Class<?> noiseChunkTarget = Class.forName("net.minecraft.world.level.levelgen.NoiseChunk");
		Class<?> cellCacheTarget = Class.forName("net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell");
		Class<?> normalNoiseTarget = Class.forName("net.minecraft.world.level.levelgen.synth.NormalNoise");
		Class<?> blendedNoiseTarget = Class.forName("net.minecraft.world.level.levelgen.synth.BlendedNoise");

		assertEquals("net.minecraft.world.level.chunk.status.ChunkStatusTasks", statusTarget.getName());
		assertEquals("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", generatorTarget.getName());
		assertEquals("net.minecraft.world.level.chunk.ChunkAccess", chunkTarget.getName());
		assertEquals("net.minecraft.world.level.levelgen.NoiseChunk", noiseChunkTarget.getName());
		assertEquals("net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell", cellCacheTarget.getName());
		assertEquals("net.minecraft.world.level.levelgen.synth.NormalNoise", normalNoiseTarget.getName());
		assertEquals("net.minecraft.world.level.levelgen.synth.BlendedNoise", blendedNoiseTarget.getName());
		assertTrue(NoiseBasedChunkGeneratorInvoker.class.isAssignableFrom(generatorTarget));
	}
}
