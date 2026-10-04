package io.github.genichimaruo.worldgenassist.common;

import java.util.concurrent.CancellationException;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** Density intermediates for doFill only: positive magnitude is never used by vanilla aquifers. */
public final class BlockDensityData {
	private BlockDensityData() { }
	public static int surfaceOffset(int height) { return Math.multiplyExact(256, height); }
	public static int sampleCount(int height) { return Math.addExact(surfaceOffset(height), SurfaceDensityData.SAMPLE_COUNT); }
	public static float canonical(float density) { return density > 0.0f ? 1.0f : density; }
	public static DensityVolume volume(TerrainDensityJob job) {
		return new DensityVolume(16, job.height(), 16, Math.multiplyExact(job.identity().chunkX(), 16),
			job.minY(), Math.multiplyExact(job.identity().chunkZ(), 16));
	}
	/** A complete vanilla interpolation cell starts repeated additions at y0=0. */
	public static DensityVolume validationCell(TerrainDensityJob job, int cell) {
		int cellsY = job.height() / 8;
		if (job.height() % 8 != 0 || Math.floorMod(job.minY(),8) != 0 || cell < 0 || cell >= 16*cellsY) {
			throw new IllegalArgumentException("Invalid aligned validation cell");
		}
		return new DensityVolume(4,8,4,Math.multiplyExact(job.identity().chunkX(),16) + (cell/cellsY%4)*4,
			job.minY() + (cell%cellsY)*8, Math.multiplyExact(job.identity().chunkZ(),16) + (cell/(cellsY*4))*4);
	}
	public static TerrainDensityResult sample(TerrainDensityJob job, RandomState state, NoiseGeneratorSettings settings) {
		if (job.workKind() != TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE
			|| !SurfaceDensityData.supports(job.noiseSettings(), settings)
			|| job.minY() != settings.noiseSettings().minY() || job.height() != settings.noiseSettings().height()) {
			throw new IllegalArgumentException("Unsupported block density job");
		}
		long started = System.nanoTime();
		double[] values = new double[job.sampleCount()];
		DensityBufferPool pool = state.acquireDensityBufferPool();
		try {
			var samplers = state.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
			try (var buffer = samplers.get(settings.noiseRouter().finalDensity()).sampleVolume(volume(job))) {
				for (int i = 0; i < surfaceOffset(job.height()); i++) {
					if ((i & 1023) == 0 && Thread.currentThread().isInterrupted()) throw new CancellationException();
					values[i] = canonical(buffer.get(i));
				}
			}
			SurfaceDensityData.fill(job, settings, samplers, values, surfaceOffset(job.height()));
		} finally { state.releaseDensityBufferPool(pool); }
		return new TerrainDensityResult(job.identity(), values, System.nanoTime() - started);
	}
}
