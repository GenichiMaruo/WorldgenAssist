package io.github.genichimaruo.worldgenassist.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface ChunkAccessNoiseChunkAccessor {
	@Accessor("noiseChunk")
	@Nullable NoiseChunk worldgenAssist$getNoiseChunk();
}
