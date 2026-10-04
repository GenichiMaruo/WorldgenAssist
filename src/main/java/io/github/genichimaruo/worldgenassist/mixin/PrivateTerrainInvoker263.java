package io.github.genichimaruo.worldgenassist.mixin;

import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import io.github.genichimaruo.worldgenassist.common.PrivateTerrainAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Descriptors verified against generated Fabric, Forge and NeoForge26.3 sources. */
@Mixin(NoiseBasedChunkGenerator.class)
public interface PrivateTerrainInvoker263 extends PrivateTerrainAccess {
	@Override @Invoker("doFill")
	void worldgenAssist$invokeDoFill(NoiseChunk noiseChunk, ChunkAccess chunk);
	@Override @Invoker("buildSurface")
	void worldgenAssist$invokeBuildSurface(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState state,
		BiomeManager biomes, Set<Holder<Biome>> possibleBiomes, MaterialRule rule);
	@Override @Invoker("generateCarvers")
	void worldgenAssist$invokeGenerateCarvers(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk,
		RandomState state, BiomeManager biomes, WorldGenRegion region, MaterialRule rule);
}
