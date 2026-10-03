package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.UUID;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import io.github.genichimaruo.worldgenassist.common.TerrainWorkKind;
import io.github.genichimaruo.worldgenassist.common.SurfaceDensityData;
import io.github.genichimaruo.worldgenassist.common.GridDensityData;

public final class RemoteDensityField {
	private final UUID jobId;
	private final int chunkX;
	private final int chunkZ;
	private final int minY;
	private final int height;
	private final int cellWidth;
	private final int cellHeight;
	private final double[] densities;
	private final TerrainDensityJob job;
	private final NoiseGeneratorSettings settings;
	private final RandomState state;
	private long remoteSamplesServed;
	private long gridSamplesServed;

	public RemoteDensityField(TerrainDensityJob job, TerrainDensityResult result) {
		this(job, result, null, null);
	}

	public RemoteDensityField(TerrainDensityJob job, TerrainDensityResult result, NoiseGeneratorSettings settings, RandomState state) {
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(result, "result");
		if (!job.identity().equals(result.identity())) {
			throw new IllegalArgumentException("Density result identity does not match its job");
		}
		if (result.densityCount() != job.sampleCount()) {
			throw new IllegalArgumentException(
				"Density result count does not match job shape: expected=" + job.sampleCount() + " actual=" + result.densityCount()
			);
		}
		jobId = job.identity().jobId();
		chunkX = job.identity().chunkPos().x();
		chunkZ = job.identity().chunkPos().z();
		minY = job.minY();
		height = job.height();
		cellWidth = job.cellWidth();
		cellHeight = job.cellHeight();
		densities = result.densities();
		this.job = job; this.settings = settings; this.state = state;
		for (double value : densities) {
			if (!Float.isFinite((float)value) || (double)(float)value != value) {
				throw new IllegalArgumentException("Remote value is not an exact finite float");
			}
		}
	}

	public TerrainWorkKind workKind() { return job.workKind(); }
	public long remoteSamplesServed() { return remoteSamplesServed; }
	public long gridSamplesServed() { return gridSamplesServed; }

	/** Only the exact assigned state, settings and generation volume can consume this field. */
	public DensitySamplerSet wrapSurfaceSamplers(RandomState actualState, NoiseGeneratorSettings actualSettings,
		DensityVolume volume, DensitySamplerSet original) {
		if (workKind() == TerrainWorkKind.DENSITY || state != actualState || settings != actualSettings
			|| volume.minBlockX() != chunkX * 16 || volume.minBlockZ() != chunkZ * 16
			|| volume.minBlockY() != minY || volume.sizeX() != 16 || volume.sizeZ() != 16 || volume.sizeY() != height
			|| volume.stepBlockX() != 1 || volume.stepBlockY() != 1 || volume.stepBlockZ() != 1) return original;
		DensityFunction aquifer = settings.aquifers().orElseThrow().surfaceLevel();
		DensityFunction material = settings.noiseRouter().chunkSurfaceLevel();
		return function -> {
			DensitySampler.Bound bound = original.get(function);
			if (workKind() == TerrainWorkKind.GRID_AND_SURFACE && function == settings.noiseRouter().finalDensity()) {
				return state.getSampler(RemoteGridSamplers.template(state, settings)).bind(bound.context());
			}
			if (function != aquifer && function != material) return bound;
			boolean isAquifer = function == aquifer;
			DensityVolume base = isAquifer ? SurfaceDensityData.aquiferVolume(job) : SurfaceDensityData.materialVolume(job);
			int offset = (workKind() == TerrainWorkKind.GRID_AND_SURFACE ? GridDensityData.surfaceOffset(height) : 0)
				+ (isAquifer ? 0 : SurfaceDensityData.AQUIFER_COUNT);
			return new DensitySampler() {
				@Override public float sampleValue(SamplerContext context, int x, int y, int z) {
					int index = surfaceIndex(base, x, y, z);
					// Material point interpolation need not share bulk-volume float bits.
					if (!isAquifer || index < 0) return bound.sampleValue(x, y, z);
					remoteSamplesServed++;
					return (float) densities[offset + index];
				}
				@Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume requested) {
					if (requested.sizeY() != 1 || requested.minBlockY() != 0
						|| surfaceIndex(base, requested.minBlockX(), 0, requested.minBlockZ()) < 0
						|| surfaceIndex(base, requested.blockX(requested.sizeX()-1), 0, requested.blockZ(requested.sizeZ()-1)) < 0
						|| requested.stepBlockX() % base.stepBlockX() != 0 || requested.stepBlockZ() % base.stepBlockZ() != 0
						// A shifted interpolated sub-volume may use different repeated-add rounding.
						|| (!isAquifer && (requested.sizeX() != 16 || requested.sizeZ() != 16
							|| requested.minBlockX() != base.minBlockX() || requested.minBlockZ() != base.minBlockZ()))) {
						bound.sampleVolume(output, requested); return;
					}
					int i = 0;
					for (int z = 0; z < requested.sizeZ(); z++) for (int x = 0; x < requested.sizeX(); x++) {
						output.set(i++, (float) densities[offset + surfaceIndex(base, requested.blockX(x), 0, requested.blockZ(z))]);
					}
					remoteSamplesServed += i;
				}
			}.bind(bound.context());
		};
	}

	private static int surfaceIndex(DensityVolume base, int x, int y, int z) {
		int dx = x - base.minBlockX(), dz = z - base.minBlockZ();
		if (y != 0 || dx < 0 || dz < 0 || dx % base.stepBlockX() != 0 || dz % base.stepBlockZ() != 0) return -1;
		int ix = dx / base.stepBlockX(), iz = dz / base.stepBlockZ();
		return ix >= base.sizeX() || iz >= base.sizeZ() ? -1 : iz * base.sizeX() + ix;
	}

	float gridValue(int node, SamplerContext context, int x, int y, int z, DensitySampler fallback) {
		int index = gridIndex(x,y,z);
		if (workKind() != TerrainWorkKind.GRID_AND_SURFACE || index < 0) return fallback.sampleValue(context,x,y,z);
		remoteSamplesServed++;
		gridSamplesServed++;
		return (float)densities[node * GridDensityData.gridSize(height) + index];
	}
	boolean copyGrid(int node, DensityBuffer output, DensityVolume requested) {
		if (workKind() != TerrainWorkKind.GRID_AND_SURFACE
			|| gridIndex(requested.minBlockX(),requested.minBlockY(),requested.minBlockZ()) < 0
			|| gridIndex(requested.blockX(requested.sizeX()-1),requested.blockY(requested.sizeY()-1),requested.blockZ(requested.sizeZ()-1)) < 0
			|| requested.stepBlockX()%4 != 0 || requested.stepBlockY()%8 != 0 || requested.stepBlockZ()%4 != 0) return false;
		int offset = node * GridDensityData.gridSize(height); int count = 0;
		for (int z=0;z<requested.sizeZ();z++) for (int x=0;x<requested.sizeX();x++) for (int y=0;y<requested.sizeY();y++) {
			output.set(count++, (float)densities[offset + gridIndex(requested.blockX(x),requested.blockY(y),requested.blockZ(z))]);
		}
		remoteSamplesServed += count; gridSamplesServed += count; return true;
	}
	private int gridIndex(int x,int y,int z) {
		int dx=x-chunkX*16, dy=y-minY, dz=z-chunkZ*16;
		if (dx<0 || dx>16 || dz<0 || dz>16 || dy<0 || dy>height || dx%4!=0 || dz%4!=0 || dy%8!=0) return -1;
		return ((dz/4)*5 + dx/4)*(height/8+1) + dy/8;
	}

	public UUID jobId() {
		return jobId;
	}

	public int chunkX() {
		return chunkX;
	}

	public int chunkZ() {
		return chunkZ;
	}

	public int minY() {
		return minY;
	}

	public int height() {
		return height;
	}

	public int cellWidth() {
		return cellWidth;
	}

	public int cellHeight() {
		return cellHeight;
	}

	/** Writes only an exact 26.3 full-block volume; never applies block state. */
	public void copyVolume(DensityVolume volume, DensityBuffer destination) {
		if (workKind() != TerrainWorkKind.DENSITY || cellWidth != 1 || cellHeight != 1
			|| volume.sizeX() != TerrainDensityJob.CHUNK_SIDE
			|| volume.sizeZ() != TerrainDensityJob.CHUNK_SIDE
			|| volume.sizeY() != height
			|| volume.minBlockX() != Math.multiplyExact(chunkX, TerrainDensityJob.CHUNK_SIDE)
			|| volume.minBlockZ() != Math.multiplyExact(chunkZ, TerrainDensityJob.CHUNK_SIDE)
			|| volume.minBlockY() != minY
			|| volume.stepBlockX() != 1 || volume.stepBlockY() != 1 || volume.stepBlockZ() != 1
			|| destination.size() != densities.length || volume.size() != densities.length) {
			throw new IllegalArgumentException("Remote density geometry does not match the 26.3 volume");
		}
		for (int index = 0; index < densities.length; index++) {
			double value = densities[index];
			float density = (float) value;
			if (!Double.isFinite(value) || !Float.isFinite(density) || (double)density != value) {
				throw new IllegalArgumentException("Remote density is not an exact finite float at " + index);
			}
			destination.set(index, density);
		}
	}

	public void copyCell(int cellXIndex, int cellYIndex, int cellZIndex, double[] destination) {
		int cellWidth = this.cellWidth;
		int cellHeight = this.cellHeight;
		int cellsXZ = TerrainDensityJob.CHUNK_SIDE / cellWidth;
		int cellsY = height / cellHeight;
		if (cellXIndex < 0 || cellXIndex >= cellsXZ || cellYIndex < 0 || cellYIndex >= cellsY || cellZIndex < 0 || cellZIndex >= cellsXZ) {
			throw new IllegalArgumentException(
				"Cell coordinate is outside job shape: " + cellXIndex + "," + cellYIndex + "," + cellZIndex
			);
		}
		int expectedLength = cellWidth * cellWidth * cellHeight;
		if (destination.length != expectedLength) {
			throw new IllegalArgumentException(
				"Destination length does not match cell shape: expected=" + expectedLength + " actual=" + destination.length
			);
		}

		int outputIndex = 0;
		for (int yInCell = cellHeight - 1; yInCell >= 0; yInCell--) {
			int yOffset = cellYIndex * cellHeight + yInCell;
			for (int xInCell = 0; xInCell < cellWidth; xInCell++) {
				int xOffset = cellXIndex * cellWidth + xInCell;
				for (int zInCell = 0; zInCell < cellWidth; zInCell++) {
					int zOffset = cellZIndex * cellWidth + zInCell;
					destination[outputIndex++] = densities[index(xOffset, yOffset, zOffset)];
				}
			}
		}
	}

	public double densityAtOffset(int xOffset, int yOffset, int zOffset) {
		return densities[index(xOffset, yOffset, zOffset)];
	}

	private int index(int xOffset, int yOffset, int zOffset) {
		if (xOffset < 0 || xOffset >= TerrainDensityJob.CHUNK_SIDE
			|| yOffset < 0 || yOffset >= height
			|| zOffset < 0 || zOffset >= TerrainDensityJob.CHUNK_SIDE) {
			throw new IndexOutOfBoundsException("Density offset outside chunk: " + xOffset + "," + yOffset + "," + zOffset);
		}
		return (yOffset * TerrainDensityJob.CHUNK_SIDE + xOffset) * TerrainDensityJob.CHUNK_SIDE + zOffset;
	}
}
