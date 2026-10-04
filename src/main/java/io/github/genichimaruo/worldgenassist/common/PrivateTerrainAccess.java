package io.github.genichimaruo.worldgenassist.common;

import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

/** Calls into the original pinned generator; implementations are Mixin invokers. */
public interface PrivateTerrainAccess {
	void worldgenAssist$invokeDoFill(NoiseChunk noiseChunk, ChunkAccess chunk);
	void worldgenAssist$invokeBuildSurface(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState state,
		BiomeManager biomes, Set<Holder<Biome>> possibleBiomes, MaterialRule rule);
	void worldgenAssist$invokeGenerateCarvers(ChunkAccess chunk, net.minecraft.world.level.levelgen.blending.Blender blender,
		NoiseChunk noiseChunk, RandomState state, BiomeManager biomes,
		net.minecraft.server.level.WorldGenRegion region, MaterialRule rule);
}
