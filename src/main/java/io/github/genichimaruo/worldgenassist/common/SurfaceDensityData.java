package io.github.genichimaruo.worldgenassist.common;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** Two bounded, block-independent fields; final blocks always remain server work. */
public final class SurfaceDensityData {
	public static final int AQUIFER_WIDTH = 27;
	public static final int AQUIFER_DEPTH = 19;
	public static final int AQUIFER_COUNT = AQUIFER_WIDTH * AQUIFER_DEPTH;
	public static final int SAMPLE_COUNT = AQUIFER_COUNT + 256;
	private SurfaceDensityData() { }

	public static boolean supports(Identifier key, NoiseGeneratorSettings settings) {
		return settings.aquifers().isPresent() && (key.equals(Identifier.withDefaultNamespace("overworld"))
			|| key.equals(Identifier.withDefaultNamespace("amplified"))
			|| key.equals(Identifier.withDefaultNamespace("large_biomes")));
	}

	public static TerrainWorkKind selectedKind(Identifier key, NoiseGeneratorSettings settings) {
		String mode = System.getProperty("worldgen_assist.remote.work_kind", System.getenv("WORLDGEN_ASSIST_REMOTE_WORK_KIND"));
		if ("density".equalsIgnoreCase(mode) || !supports(key, settings)) return TerrainWorkKind.DENSITY;
		if (CompleteTerrainMode.requested() && TerrainDecisionData.supports(key, settings)
			&& settings.defaultBlock() == net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()) return TerrainWorkKind.COMPLETE_TERRAIN;
		if ("surface".equalsIgnoreCase(mode)) return TerrainWorkKind.SURFACE_FIELDS;
		if ("block".equalsIgnoreCase(mode)) return TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE;
		if ("decisions".equalsIgnoreCase(mode) && TerrainDecisionData.allowedByOperator() && TerrainDecisionData.supports(key,settings)) {
			return TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE;
		}
		return GridDensityData.supports(settings) ? TerrainWorkKind.GRID_AND_SURFACE : TerrainWorkKind.SURFACE_FIELDS;
	}

	public static DensityVolume aquiferVolume(TerrainDensityJob job) {
		return new DensityVolume(AQUIFER_WIDTH, 1, AQUIFER_DEPTH,
			Math.multiplyExact(job.identity().chunkX(), 16) - 64, 0,
			Math.multiplyExact(job.identity().chunkZ(), 16) - 32, 4, 1, 4);
	}

	public static DensityVolume materialVolume(TerrainDensityJob job) {
		return new DensityVolume(16, 1, 16, Math.multiplyExact(job.identity().chunkX(), 16), 0,
			Math.multiplyExact(job.identity().chunkZ(), 16));
	}

	public static TerrainDensityResult sample(TerrainDensityJob job, RandomState state, NoiseGeneratorSettings settings) {
		if (job.workKind() != TerrainWorkKind.SURFACE_FIELDS || !supports(job.noiseSettings(), settings)) {
			throw new IllegalArgumentException("Unsupported surface field job");
		}
		long start = System.nanoTime();
		double[] values = new double[SAMPLE_COUNT];
		DensityBufferPool pool = state.acquireDensityBufferPool();
		try {
			DensitySamplerSet samplers = state.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
			fill(job, settings, samplers, values, 0);
		} finally { state.releaseDensityBufferPool(pool); }
		return new TerrainDensityResult(job.identity(), values, System.nanoTime() - start);
	}

	public static void fill(TerrainDensityJob job, NoiseGeneratorSettings settings, DensitySamplerSet samplers,
		double[] values, int offset) {
		copy(samplers.get(settings.aquifers().orElseThrow().surfaceLevel()), aquiferVolume(job), values, offset);
		copy(samplers.get(settings.noiseRouter().chunkSurfaceLevel()), materialVolume(job), values, offset + AQUIFER_COUNT);
	}

	private static void copy(DensitySampler.Bound sampler, DensityVolume volume, double[] values, int offset) {
		try (ScopedDensityBuffer buffer = sampler.sampleVolume(volume)) {
			for (int i = 0; i < volume.size(); i++) values[offset + i] = buffer.get(i);
		}
	}
}
