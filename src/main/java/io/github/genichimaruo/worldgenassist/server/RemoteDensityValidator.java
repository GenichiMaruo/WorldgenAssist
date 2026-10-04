package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.random.RandomGenerator;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainWorkKind;
import io.github.genichimaruo.worldgenassist.common.SurfaceDensityData;
import io.github.genichimaruo.worldgenassist.common.GridDensityData;
import io.github.genichimaruo.worldgenassist.common.BlockDensityData;
import io.github.genichimaruo.worldgenassist.common.TerrainDecisionData;

/** Independent, bounded server sampling of the 26.3 block-indexed float volume. */
final class RemoteDensityValidator {
	private static final int VALUES_PER_GROUP = 128;

	private RemoteDensityValidator() { }

	static ValidationMetrics validate(
		TerrainDensityJob job,
		TerrainDensityResult result,
		int sampleGroups,
		RandomState randomState,
		NoiseGeneratorSettings settings,
		NoiseSettings noiseSettings,
		RandomGenerator random
	) {
		return prepare(job, sampleGroups, randomState, settings, noiseSettings, random).compare(result);
	}

	static Prepared prepare(TerrainDensityJob job, int sampleGroups, RandomState randomState,
		NoiseGeneratorSettings settings, NoiseSettings noiseSettings, RandomGenerator random) {
		long start = System.nanoTime();
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(randomState, "randomState");
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(noiseSettings, "noiseSettings");
		Objects.requireNonNull(random, "random");
		if (job.workKind() == TerrainWorkKind.COMPLETE_TERRAIN) throw new IllegalArgumentException("Complete terrain requires explicit whole-chunk audit preparation");
		if (job.cellWidth() != 1 || job.cellHeight() != 1
			|| job.minY() != noiseSettings.minY() || job.height() != noiseSettings.height()) {
			throw new RemoteDensityValidationException("Result geometry does not match the authoritative 26.3 volume");
		}
		if (sampleGroups < 0 || sampleGroups > RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS) {
			throw new IllegalArgumentException("Invalid validation group count: " + sampleGroups);
		}
		if (sampleGroups == 0) { return new Prepared(job, 0, new int[0], new float[0], 0L); }
		if (job.workKind() == TerrainWorkKind.GRID_AND_SURFACE) {
			return prepareGrid(job, sampleGroups, randomState, settings, random, start);
		}
		if (job.workKind() == TerrainWorkKind.SURFACE_FIELDS) {
			return prepareSurface(job, sampleGroups, randomState, settings, random, start);
		}
		if (job.workKind() == TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE
			|| (job.workKind() == TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE && GridDensityData.supports(settings))) {
			return prepareAlignedCells(job,sampleGroups,randomState,settings,random,start);
		}

		boolean block = job.workKind() == TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE;
		if (block && !SurfaceDensityData.supports(job.noiseSettings(), settings)) {
			throw new RemoteDensityValidationException("Unsupported block density settings");
		}
		int totalValues = block ? BlockDensityData.surfaceOffset(job.height()) : job.sampleCount();
		int totalGroups = Math.floorDiv(totalValues + VALUES_PER_GROUP - 1, VALUES_PER_GROUP);
		int[] groups = selectCells(totalGroups, Math.min(sampleGroups, totalGroups), random);
		// Keep the original bottom of each column: interpolated float volumes use
		// repeated additions within cells, so arbitrary point/sub-column sampling
		// need not preserve the exact bits produced by vanilla's full volume.
		Map<Integer, Integer> columnHeights = new HashMap<>();
		for (int group : groups) {
			int end = Math.min(totalValues, (group + 1) * VALUES_PER_GROUP);
			for (int index = group * VALUES_PER_GROUP; index < end; index++) {
				columnHeights.merge(index / job.height(), index % job.height() + 1, Math::max);
			}
		}
		Map<Integer, float[]> sampledColumns = new HashMap<>();
		int chunkMinX = Math.multiplyExact(job.identity().chunkX(), TerrainDensityJob.CHUNK_SIDE);
		int chunkMinZ = Math.multiplyExact(job.identity().chunkZ(), TerrainDensityJob.CHUNK_SIDE);
		int[] indices = new int[Math.min(sampleGroups, totalGroups) * VALUES_PER_GROUP];
		float[] expectedValues = new float[indices.length];
		int validated = 0;
		// A private context enables vanilla's internal DAG caches without sharing
		// mutable buffers between validation invocations or generation workers.
		DensityBufferPool pool = randomState.acquireDensityBufferPool();
		try {
			DensitySampler.Bound sampler = randomState.samplersWithContext(SamplerContext.builder()
				.useBufferArena(pool).enableCaches().build()).get(settings.noiseRouter().finalDensity());
			for (int group : groups) {
				int begin = group * VALUES_PER_GROUP;
				int end = Math.min(totalValues, begin + VALUES_PER_GROUP);
				for (int index = begin; index < end; index++) {
					if (Thread.currentThread().isInterrupted()) {
						throw new CancellationException("Remote density validation interrupted");
					}
					int y = index % job.height();
					int x = (index / job.height()) % TerrainDensityJob.CHUNK_SIDE;
					int z = index / (job.height() * TerrainDensityJob.CHUNK_SIDE);
					int columnKey = z * TerrainDensityJob.CHUNK_SIDE + x;
					float[] column = sampledColumns.get(columnKey);
					if (column == null) {
						int sampledHeight = columnHeights.get(columnKey);
						DensityVolume columnVolume = new DensityVolume(1, sampledHeight, 1,
							chunkMinX + x, job.minY(), chunkMinZ + z);
						column = new float[sampledHeight];
						try (ScopedDensityBuffer buffer = sampler.sampleVolume(columnVolume)) {
							for (int columnY = 0; columnY < column.length; columnY++) {
								column[columnY] = buffer.get(columnY);
							}
						}
						sampledColumns.put(columnKey, column);
					}
					indices[validated] = index;
					expectedValues[validated] = block ? BlockDensityData.canonical(column[y]) : column[y];
					validated++;
				}
			}
		} finally {
			randomState.releaseDensityBufferPool(pool);
		}
		if (block) {
			return withSurface(job,sampleGroups,randomState,settings,random,start,groups.length,
				java.util.Arrays.copyOf(indices,validated),java.util.Arrays.copyOf(expectedValues,validated));
		}
		return new Prepared(job, groups.length, java.util.Arrays.copyOf(indices, validated),
			java.util.Arrays.copyOf(expectedValues, validated), System.nanoTime() - start);
	}

	private static Prepared prepareAlignedCells(TerrainDensityJob job,int sampleGroups,RandomState state,
		NoiseGeneratorSettings settings,RandomGenerator random,long start) {
		boolean decisions = job.workKind() == TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE;
		if (!SurfaceDensityData.supports(job.noiseSettings(),settings) || !GridDensityData.supports(settings)
			|| (decisions && !TerrainDecisionData.supports(job.noiseSettings(),settings))) {
			throw new RemoteDensityValidationException("Unsupported aligned terrain validation context");
		}
		int totalCells = 16 * (job.height()/8);
		int[] cells = selectCells(totalCells,Math.min(sampleGroups,totalCells),random);
		int[] indices = new int[cells.length*128];
		float[] expected = new float[indices.length];
		var full = BlockDensityData.volume(job);
		var pool = state.acquireDensityBufferPool();
		int count = 0;
		try {
			var samplers = state.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
			var sampler = samplers.get(settings.noiseRouter().finalDensity());
			// Use only authoritative samples, including the aquifer surface prepass.
			var aquifer = decisions ? TerrainDecisionData.aquifer(state,settings,samplers,full) : null;
			for(int cell:cells) {
				if(Thread.currentThread().isInterrupted()) throw new CancellationException("Aligned validation interrupted");
				var volume = BlockDensityData.validationCell(job,cell);
				// A whole aligned4x8x4 cell has y0=0 in fillCell. Sampling a shifted
				// single point would use different repeated-add float rounding.
				try(var buffer = sampler.sampleVolume(volume)) {
					for(int z=0;z<4;z++) for(int x=0;x<4;x++) for(int y=7;y>=0;y--) {
						int bx=volume.blockX(x),by=volume.blockY(y),bz=volume.blockZ(z);
						float density = buffer.get(volume.indexUnchecked(x,y,z));
						indices[count] = full.indexOfBlock(bx,by,bz);
						if(decisions) {
							var substance = aquifer.computeSubstance(bx,by,bz,density);
							expected[count++] = TerrainDecisionData.encode(substance,aquifer.shouldScheduleFluidUpdate(),settings);
						} else { expected[count++] = BlockDensityData.canonical(density); }
					}
				}
			}
		} finally { state.releaseDensityBufferPool(pool); }
		return withSurface(job,sampleGroups,state,settings,random,start,cells.length,indices,expected);
	}

	private static Prepared withSurface(TerrainDensityJob job,int sampleGroups,RandomState state,
		NoiseGeneratorSettings settings,RandomGenerator random,long start,int groups,int[] indices,float[] values) {
		TerrainDensityJob surface = new TerrainDensityJob(job.identity(),job.worldSeed(),job.generateStructures(),
			job.noiseSettings(),job.minY(),job.height(),1,1,TerrainWorkKind.SURFACE_FIELDS);
		Prepared extra = prepareSurface(surface,sampleGroups,state,settings,random,System.nanoTime());
		int[] combined = java.util.Arrays.copyOf(indices,indices.length+extra.indices.length);
		float[] expected = java.util.Arrays.copyOf(values,combined.length);
		for(int i=0;i<extra.indices.length;i++) {
			combined[indices.length+i] = BlockDensityData.surfaceOffset(job.height())+extra.indices[i];
			expected[indices.length+i] = extra.expected[i];
		}
		return new Prepared(job,groups+extra.groups,combined,expected,System.nanoTime()-start);
	}

	private static Prepared prepareGrid(TerrainDensityJob job, int sampleGroups, RandomState state,
		NoiseGeneratorSettings settings, RandomGenerator random, long start) {
		if (!SurfaceDensityData.supports(job.noiseSettings(),settings) || !GridDensityData.supports(settings)) {
			throw new RemoteDensityValidationException("Unsupported terrain grid settings");
		}
		// Compact grid inputs have no block-step repeated-add dependency. Sample
		// distinct secret points instead of paying for sixteen values per group.
		final int groupSize=1; int total=job.sampleCount();
		int[] groups=selectCells((total+groupSize-1)/groupSize,Math.min(sampleGroups,(total+groupSize-1)/groupSize),random);
		int[] indices=new int[groups.length*groupSize]; float[] expected=new float[indices.length]; int count=0;
		int gridSize=GridDensityData.gridSize(job.height()), surfaceOffset=GridDensityData.surfaceOffset(job.height());
		DensityBufferPool pool=state.acquireDensityBufferPool();
		try {
			var samplers=state.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
			var inputFunctions=GridDensityData.inputs(settings); var base=GridDensityData.volume(job);
			float[] material=null;
			var aquiferVolume=SurfaceDensityData.aquiferVolume(job);
			for (int group:groups) {
				if (Thread.currentThread().isInterrupted()) throw new CancellationException("Grid validation interrupted");
				int index=group*groupSize,end=Math.min(total,index+groupSize);
				while(index<end) {
					if(index<surfaceOffset) {
						int node=index/gridSize, within=index%gridSize, y=within%base.sizeY();
						int x=(within/base.sizeY())%5,z=within/(base.sizeY()*5);
						int length=Math.min(end-index,base.sizeY()-y);
						var column=new DensityVolume(1,length,1,base.blockX(x),base.blockY(y),base.blockZ(z),4,8,4);
						try(var buffer=samplers.get(inputFunctions.get(node)).sampleVolume(column)) {
							for(int i=0;i<length;i++){indices[count]=index++;expected[count++]=buffer.get(i);}
						}
					} else {
						int local=index-surfaceOffset;
						indices[count]=index++;
						if(local<SurfaceDensityData.AQUIFER_COUNT) {
							int x=local%SurfaceDensityData.AQUIFER_WIDTH,z=local/SurfaceDensityData.AQUIFER_WIDTH;
							try(var buffer=samplers.get(settings.aquifers().orElseThrow().surfaceLevel()).sampleVolume(
								new DensityVolume(1,1,1,aquiferVolume.blockX(x),0,aquiferVolume.blockZ(z),4,1,4))) {
								expected[count++]=buffer.get(0);
							}
						} else {
							// Material interpolation needs its original full volume to preserve float rounding.
							if(material==null) {
								material=new float[256];
								try(var buffer=samplers.get(settings.noiseRouter().chunkSurfaceLevel()).sampleVolume(SurfaceDensityData.materialVolume(job))) {
									for(int i=0;i<256;i++)material[i]=buffer.get(i);
								}
							}
							expected[count++]=material[local-SurfaceDensityData.AQUIFER_COUNT];
						}
					}
				}
			}
		} finally {state.releaseDensityBufferPool(pool);}
		return new Prepared(job,groups.length,java.util.Arrays.copyOf(indices,count),java.util.Arrays.copyOf(expected,count),System.nanoTime()-start);
	}

	private static Prepared prepareSurface(TerrainDensityJob job, int sampleGroups, RandomState state,
		NoiseGeneratorSettings settings, RandomGenerator random, long start) {
		if (!SurfaceDensityData.supports(job.noiseSettings(), settings)) {
			throw new RemoteDensityValidationException("Unsupported surface settings");
		}
		final int groupSize = 16;
		int[] groups = selectCells((SurfaceDensityData.SAMPLE_COUNT + groupSize - 1) / groupSize,
			Math.min(sampleGroups, (SurfaceDensityData.SAMPLE_COUNT + groupSize - 1) / groupSize), random);
		int[] indices = new int[groups.length * groupSize];
		float[] expected = new float[indices.length];
		int count = 0;
		DensityBufferPool pool = state.acquireDensityBufferPool();
		try {
			var samplers = state.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
			var aquifer = samplers.get(settings.aquifers().orElseThrow().surfaceLevel());
			float[] material = null;
			DensityVolume base = SurfaceDensityData.aquiferVolume(job);
			for (int group : groups) {
				if (Thread.currentThread().isInterrupted()) throw new CancellationException("Surface validation interrupted");
				int index = group * groupSize;
				int end = Math.min(SurfaceDensityData.SAMPLE_COUNT, index + groupSize);
				while (index < end) {
					if (index < SurfaceDensityData.AQUIFER_COUNT) {
						int x = index % SurfaceDensityData.AQUIFER_WIDTH;
						int z = index / SurfaceDensityData.AQUIFER_WIDTH;
						int length = Math.min(end - index, SurfaceDensityData.AQUIFER_WIDTH - x);
						DensityVolume row = new DensityVolume(length, 1, 1, base.blockX(x), 0, base.blockZ(z), 4, 1, 4);
						try (ScopedDensityBuffer buffer = aquifer.sampleVolume(row)) {
							for (int i = 0; i < length; i++) {
								indices[count] = index++; expected[count++] = buffer.get(i);
							}
						}
					} else {
						// Interpolated volumes use repeated float additions. Sample the
						// original 16x16 volume, never a shifted point/sub-volume.
						if (material == null) {
							material = new float[256];
							try (ScopedDensityBuffer buffer = samplers.get(settings.noiseRouter().chunkSurfaceLevel())
								.sampleVolume(SurfaceDensityData.materialVolume(job))) {
								for (int i = 0; i < material.length; i++) material[i] = buffer.get(i);
							}
						}
						indices[count] = index; expected[count++] = material[index++ - SurfaceDensityData.AQUIFER_COUNT];
					}
				}
			}
		} finally { state.releaseDensityBufferPool(pool); }
		return new Prepared(job, groups.length, java.util.Arrays.copyOf(indices, count),
			java.util.Arrays.copyOf(expected, count), System.nanoTime() - start);
	}

	/** Immutable server-owned sample; positions are never sent to the worker. */
	static final class Prepared {
		private final TerrainDensityJob job;
		private final int groups;
		private final int[] indices;
		private final float[] expected;
		private final long prepareNanos;
		private final io.github.genichimaruo.worldgenassist.common.CompleteTerrainData expectedTerrain;

		private Prepared(TerrainDensityJob job, int groups, int[] indices, float[] expected, long prepareNanos) {
			this.job = job; this.groups = groups; this.indices = indices; this.expected = expected;
			this.prepareNanos = prepareNanos;
			expectedTerrain = null;
		}
		private Prepared(TerrainDensityJob job, io.github.genichimaruo.worldgenassist.common.CompleteTerrainData expected, long elapsed) {
			this.job = job; expectedTerrain = expected; prepareNanos = elapsed;
			groups = expected == null ? 0 : 1; indices = new int[0]; this.expected = new float[0];
		}
		static Prepared completeTerrain(TerrainDensityJob job, io.github.genichimaruo.worldgenassist.common.CompleteTerrainData expected, long elapsed) {
			if (job.workKind() != TerrainWorkKind.COMPLETE_TERRAIN) throw new IllegalArgumentException("Not a complete terrain job");
			return new Prepared(job, expected, elapsed);
		}

		long prepareNanos() { return prepareNanos; }
		ValidationMetrics compare(TerrainDensityResult result) {
			long started = System.nanoTime();
			if (!job.identity().equals(result.identity()) || result.densityCount() != job.sampleCount()) {
				throw new RemoteDensityValidationException("Result identity or volume size does not match its job");
			}
			if (job.workKind() == TerrainWorkKind.COMPLETE_TERRAIN) {
				if (!result.hasCompleteTerrain() || result.completeTerrain().minY() != job.minY()
					|| result.completeTerrain().height() != job.height()) throw new RemoteDensityValidationException("Invalid complete terrain shape");
				if (expectedTerrain != null && !expectedTerrain.equals(result.completeTerrain())) {
					throw new RemoteDensityValidationException("Independent whole-terrain audit mismatch");
				}
				return new ValidationMetrics(groups, expectedTerrain == null ? 0 : job.sampleCount(), prepareNanos + System.nanoTime() - started);
			}
			if (result.hasCompleteTerrain()) throw new RemoteDensityValidationException("Complete terrain returned for an intermediate job");
			if(job.workKind() == TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE
				&& !result.hasTerrainCodes(BlockDensityData.surfaceOffset(job.height()))) {
				for(int i=0;i<BlockDensityData.surfaceOffset(job.height());i++) {
					if(!TerrainDecisionData.validCode(result.densityAt(i))) {
						throw new RemoteDensityValidationException("Invalid terrain decision at index " + i);
					}
				}
			}
			for (int index = 0; index < indices.length; index++) {
				requireExact(expected[index], result.densityAt(indices[index]), indices[index]);
			}
			return new ValidationMetrics(groups, indices.length, prepareNanos + System.nanoTime() - started);
		}
	}

	static int[] selectCells(int totalCells, int sampleCells, RandomGenerator random) {
		if (totalCells < 1 || sampleCells < 0 || sampleCells > totalCells) {
			throw new IllegalArgumentException("Invalid validation selection geometry");
		}
		Objects.requireNonNull(random, "random");
		boolean[] selected = new boolean[totalCells];
		int remaining = sampleCells;
		while (remaining > 0) {
			int candidate = random.nextInt(totalCells);
			if (!selected[candidate]) { selected[candidate] = true; remaining--; }
		}
		int[] output = new int[sampleCells];
		int index = 0;
		for (int cell = 0; cell < selected.length; cell++) {
			if (selected[cell]) { output[index++] = cell; }
		}
		return output;
	}

	static void requireExact(double expected, double actual, int densityIndex) {
		float value = (float) actual;
		if (!Double.isFinite(actual) || (double)value != actual
			|| Float.floatToRawIntBits((float)expected) != Float.floatToRawIntBits(value)) {
			throw new RemoteDensityValidationException(
				"Remote density validation failed at index " + densityIndex + ": expected=" + expected + ", actual=" + actual
			);
		}
	}

	record ValidationMetrics(int sampledCells, int sampledValues, long elapsedNanos) {
		private static final ValidationMetrics NONE = new ValidationMetrics(0, 0, 0L);
		ValidationMetrics {
			if (sampledCells < 0 || sampledValues < 0 || elapsedNanos < 0L) {
				throw new IllegalArgumentException("Validation metrics must not be negative");
			}
		}
	}

	static final class RemoteDensityValidationException extends IllegalArgumentException {
		RemoteDensityValidationException(String message) { super(message); }
	}
}
