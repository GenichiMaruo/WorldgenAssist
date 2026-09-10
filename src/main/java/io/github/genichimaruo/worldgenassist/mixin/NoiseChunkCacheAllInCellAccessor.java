package io.github.genichimaruo.worldgenassist.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public interface NoiseChunkCacheAllInCellAccessor {
	@Accessor("noiseFiller")
	DensityFunction worldgenAssist$getNoiseFiller();
}
