package io.github.genichimaruo.worldgenassist.server;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * Explicit opt-in for a custom generator whose terrain stage delegates to the
 * supplied vanilla noise generator's buildTerrain/doFill path. The delegate must
 * use the world's RandomState and registry-backed noise settings for the same
 * chunk. Other generation stages remain the custom generator's responsibility.
 *
 * <p>Implementations with their own density sampler or block-writing path must
 * not opt in: their results cannot be installed by the vanilla doFill Mixin.
 */
public interface RemoteDensityCompatibleGenerator {
	NoiseBasedChunkGenerator worldgenAssist$noiseDelegate();
}
