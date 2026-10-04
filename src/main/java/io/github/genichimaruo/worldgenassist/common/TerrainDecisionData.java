package io.github.genichimaruo.worldgenassist.common;

import java.util.concurrent.CancellationException;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** Seven bounded aquifer classifications, not arbitrary block states or world mutations. */
public final class TerrainDecisionData {
	private TerrainDecisionData() { }
	public static boolean allowedByOperator() {
		return "true".equalsIgnoreCase(System.getProperty("worldgen_assist.remote.allow_terrain_decisions",
			System.getenv("WORLDGEN_ASSIST_REMOTE_ALLOW_TERRAIN_DECISIONS")));
	}
	public static boolean supports(Identifier key, NoiseGeneratorSettings settings) {
		return SurfaceDensityData.supports(key, settings) && GridDensityData.supports(settings)
			&& settings.defaultFluid() == Blocks.WATER.defaultBlockState()
			&& !SharedConstants.DEBUG_AQUIFERS && !SharedConstants.DEBUG_DISABLE_FLUID_GENERATION;
	}
	public static boolean validCode(double value) { return value >= 0 && value <= 6 && value == (int)value; }
	public static float encode(BlockState state, boolean update, NoiseGeneratorSettings settings) {
		if (state == null && !update) return 0;
		if (state == Blocks.AIR.defaultBlockState()) return update ? 2 : 1;
		if (state == settings.defaultFluid()) return update ? 4 : 3;
		if (state == Blocks.LAVA.defaultBlockState()) return update ? 6 : 5;
		throw new IllegalArgumentException("Unsupported aquifer decision");
	}
	public static BlockState substance(int code, NoiseGeneratorSettings settings) {
		return switch (code) {
			case 0 -> null;
			case 1,2 -> Blocks.AIR.defaultBlockState();
			case 3,4 -> settings.defaultFluid();
			case 5,6 -> Blocks.LAVA.defaultBlockState();
			default -> throw new IllegalArgumentException("Invalid terrain decision code");
		};
	}
	public static boolean schedulesFluid(int code) { return code == 2 || code == 4 || code == 6; }
	/** Exact target26.3 NoiseBasedChunkGenerator.createFluidPicker semantics. */
	public static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings) {
		var lava = new Aquifer.FluidStatus(-54,Blocks.LAVA.defaultBlockState());
		var sea = new Aquifer.FluidStatus(settings.seaLevel(),settings.defaultFluid());
		var empty = new Aquifer.FluidStatus(DimensionType.MIN_Y*2,Blocks.AIR.defaultBlockState());
		return (x,y,z) -> SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? empty : y < Math.min(-54,settings.seaLevel()) ? lava : sea;
	}
	public static Aquifer aquifer(RandomState state, NoiseGeneratorSettings settings, DensitySamplerSet samplers, DensityVolume volume) {
		return settings.aquifers().orElseThrow().create(samplers,
			state.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer")),volume,fluidPicker(settings));
	}
	public static TerrainDensityResult sample(TerrainDensityJob job, RandomState state, NoiseGeneratorSettings settings) {
		if (job.workKind() != TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE || !supports(job.noiseSettings(),settings)
			|| job.minY() != settings.noiseSettings().minY() || job.height() != settings.noiseSettings().height()) {
			throw new IllegalArgumentException("Unsupported terrain decision job");
		}
		long started = System.nanoTime();
		byte[] codes = new byte[BlockDensityData.surfaceOffset(job.height())];
		float[] values = new float[SurfaceDensityData.SAMPLE_COUNT];
		var volume = BlockDensityData.volume(job);
		var pool = state.acquireDensityBufferPool();
		try {
			var samplers = state.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
			var aquifer = aquifer(state,settings,samplers,volume);
			try (var buffer = samplers.get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
				for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
					if (Thread.currentThread().isInterrupted()) throw new CancellationException();
					for (int y=job.height()-1;y>=0;y--) {
						int index = volume.indexUnchecked(x,y,z);
						BlockState substance = aquifer.computeSubstance(volume.blockX(x),volume.blockY(y),volume.blockZ(z),buffer.get(index));
						codes[index] = (byte)encode(substance,aquifer.shouldScheduleFluidUpdate(),settings);
					}
				}
			}
			double[] surface = new double[SurfaceDensityData.SAMPLE_COUNT];
			SurfaceDensityData.fill(job,settings,samplers,surface,0);
			for (int i = 0; i < surface.length; i++) values[i] = (float)surface[i];
		} finally { state.releaseDensityBufferPool(pool); }
		return TerrainDensityResult.fromTerrainCodes(job.identity(),codes,values,System.nanoTime()-started);
	}
}
