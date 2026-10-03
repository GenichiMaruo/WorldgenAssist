package io.github.genichimaruo.worldgenassist.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;

/** Bounded inputs, not final block densities. The server retains vanilla interpolation. */
public final class GridDensityData {
	public static final int INPUT_COUNT = 5;
	// Weak identity keys avoid hashing the entire settings density graph on each chunk.
	private static final Map<NoiseGeneratorSettings, List<DensityFunction>> INPUTS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
	private GridDensityData() { }

	public static List<DensityFunction> inputs(NoiseGeneratorSettings settings) {
		return INPUTS.computeIfAbsent(settings, ignored -> {
			List<DensityFunction> inputs = new ArrayList<>();
			new DfRewriteRule() {
				@Override public DensityFunction rewrite(DensityFunction function) {
					function = DfRewriteRule.INLINE_REFERENCE.rewrite(function);
					if (function instanceof InterpolatedFunction node) {
						if (node.cellSizeXz() != 4 || node.cellSizeY() != 8) throw new IllegalArgumentException("Unsupported terrain interpolator");
						inputs.add(node.input());
						return function;
					}
					return function.rewriteChildren(this);
				}
			}.rewrite(settings.noiseRouter().finalDensity());
			if (inputs.size() != INPUT_COUNT) throw new IllegalArgumentException("Unexpected terrain grid count: " + inputs.size());
			return List.copyOf(inputs);
		});
	}
	public static boolean supports(NoiseGeneratorSettings settings) {
		try { inputs(settings); return settings.noiseSettings().height() % 8 == 0 && Math.floorMod(settings.noiseSettings().minY(), 8) == 0; }
		catch (IllegalArgumentException error) { return false; }
	}
	public static int gridSize(int height) { return Math.multiplyExact(25, height / 8 + 1); }
	public static int surfaceOffset(int height) { return Math.multiplyExact(INPUT_COUNT, gridSize(height)); }
	public static int sampleCount(int height) { return Math.addExact(surfaceOffset(height), SurfaceDensityData.SAMPLE_COUNT); }
	public static DensityVolume volume(TerrainDensityJob job) {
		return new DensityVolume(5, job.height() / 8 + 1, 5, Math.multiplyExact(job.identity().chunkX(), 16), job.minY(),
			Math.multiplyExact(job.identity().chunkZ(), 16), 4, 8, 4);
	}
	public static TerrainDensityResult sample(TerrainDensityJob job, RandomState state, NoiseGeneratorSettings settings) {
		if (job.workKind() != TerrainWorkKind.GRID_AND_SURFACE || !SurfaceDensityData.supports(job.noiseSettings(), settings) || !supports(settings)) {
			throw new IllegalArgumentException("Unsupported terrain grid job");
		}
		long started = System.nanoTime(); double[] values = new double[job.sampleCount()];
		DensityBufferPool pool = state.acquireDensityBufferPool();
		try {
			var samplers = state.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
			var volume = volume(job); var inputs = inputs(settings);
			for (int node = 0; node < INPUT_COUNT; node++) {
				if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
				try (var buffer = samplers.get(inputs.get(node)).sampleVolume(volume)) {
					for (int i = 0; i < volume.size(); i++) values[node * volume.size() + i] = buffer.get(i);
				}
			}
			SurfaceDensityData.fill(job, settings, samplers, values, surfaceOffset(job.height()));
		} finally { state.releaseDensityBufferPool(pool); }
		return new TerrainDensityResult(job.identity(), values, System.nanoTime() - started);
	}
}
