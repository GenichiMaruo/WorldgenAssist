package io.github.genichimaruo.worldgenassist.common;

import java.time.Duration;
import java.util.Objects;

public final class TerrainDensityResult {
	public static final double MAX_ABSOLUTE_DENSITY = 1_000_000.0;
	public static final long MAX_CLIENT_COMPUTE_NANOS = Duration.ofHours(1).toNanos();

	private final TerrainJobIdentity identity;
	private final double[] densities;
	private final float[] floatDensities;
	private final byte[] terrainCodes;
	private final float[] terrainSurface;
	private final CompleteTerrainData completeTerrain;
	private final long clientComputeNanos;

	public TerrainDensityResult(TerrainJobIdentity identity, double[] densities, long clientComputeNanos) {
		this.identity = Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(densities, "densities");
		if (densities.length < 1 || densities.length > TerrainDensityJob.MAX_TERRAIN_SAMPLE_COUNT) {
			throw new IllegalArgumentException(
				"Density count must be between 1 and " + TerrainDensityJob.MAX_TERRAIN_SAMPLE_COUNT + ": " + densities.length
			);
		}
		this.densities = densities.clone();
		this.floatDensities = null;
		this.terrainCodes = null; this.terrainSurface = null; completeTerrain = null;
		for (int index = 0; index < this.densities.length; index++) {
			double density = this.densities[index];
			if (!Double.isFinite(density) || Math.abs(density) > MAX_ABSOLUTE_DENSITY) {
				throw new IllegalArgumentException("Invalid density at index " + index + ": " + density);
			}
		}
		if (clientComputeNanos < 0L || clientComputeNanos > MAX_CLIENT_COMPUTE_NANOS) {
			throw new IllegalArgumentException(
				"Client compute time must be between 0 and " + MAX_CLIENT_COMPUTE_NANOS + " nanoseconds: " + clientComputeNanos
			);
		}
		this.clientComputeNanos = clientComputeNanos;
	}

	/** Immutable exact float storage for the 26.3 transport; no double expansion. */
	public static TerrainDensityResult fromFloats(TerrainJobIdentity identity, float[] values, long computeNanos) {
		return new TerrainDensityResult(identity, values, computeNanos);
	}

	private TerrainDensityResult(TerrainJobIdentity identity, float[] values, long computeNanos) {
		this.identity = Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(values, "values");
		if (values.length < 1 || values.length > TerrainDensityJob.MAX_TERRAIN_SAMPLE_COUNT
			|| computeNanos < 0 || computeNanos > MAX_CLIENT_COMPUTE_NANOS) {
			throw new IllegalArgumentException("Invalid float result count or compute time");
		}
		floatDensities = values.clone(); densities = null; terrainCodes = null; terrainSurface = null; completeTerrain = null;
		for (float value : floatDensities) {
			if (!Float.isFinite(value) || Math.abs(value) > MAX_ABSOLUTE_DENSITY) {
				throw new IllegalArgumentException("Invalid float density");
			}
		}
		clientComputeNanos = computeNanos;
	}

	/** Validated immutable byte codes; this provenance permits avoiding later repeated domain scans. */
	public static TerrainDensityResult fromTerrainCodes(TerrainJobIdentity identity, byte[] codes, float[] surface, long computeNanos) {
		return new TerrainDensityResult(identity, codes, surface, computeNanos);
	}
	private TerrainDensityResult(TerrainJobIdentity identity, byte[] codes, float[] surface, long computeNanos) {
		this.identity = Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(codes, "codes"); Objects.requireNonNull(surface, "surface");
		if (codes.length < 2048 || codes.length > TerrainDensityJob.MAX_SAMPLE_COUNT || codes.length % 2048 != 0
			|| surface.length != SurfaceDensityData.SAMPLE_COUNT || computeNanos < 0 || computeNanos > MAX_CLIENT_COMPUTE_NANOS) {
			throw new IllegalArgumentException("Invalid terrain code geometry or timing");
		}
		terrainCodes = codes.clone(); terrainSurface = surface.clone(); densities = null; floatDensities = null; completeTerrain = null;
		for (byte code : terrainCodes) if (code < 0 || code > 6) throw new IllegalArgumentException("Invalid terrain code");
		for (float value : terrainSurface) if (!Float.isFinite(value) || Math.abs(value) > MAX_ABSOLUTE_DENSITY) {
			throw new IllegalArgumentException("Invalid terrain surface value");
		}
		clientComputeNanos = computeNanos;
	}
	public boolean hasTerrainCodes(int count) { return terrainCodes != null && terrainCodes.length == count; }
	public static TerrainDensityResult fromCompleteTerrain(TerrainJobIdentity identity, CompleteTerrainData data, long computeNanos) {
		return new TerrainDensityResult(identity, data, computeNanos);
	}
	private TerrainDensityResult(TerrainJobIdentity identity, CompleteTerrainData data, long computeNanos) {
		this.identity = Objects.requireNonNull(identity);
		completeTerrain = Objects.requireNonNull(data);
		if (computeNanos < 0 || computeNanos > MAX_CLIENT_COMPUTE_NANOS) throw new IllegalArgumentException("Invalid terrain timing");
		clientComputeNanos = computeNanos;
		densities = null; floatDensities = null; terrainCodes = null; terrainSurface = null;
	}
	public boolean hasCompleteTerrain() { return completeTerrain != null; }
	public CompleteTerrainData completeTerrain() {
		if (completeTerrain == null) throw new IllegalStateException("Not a complete terrain result");
		return completeTerrain;
	}
	public byte[] terrainCodes() {
		if (terrainCodes == null) throw new IllegalStateException("Not a packed terrain result");
		return terrainCodes.clone();
	}
	public float[] terrainSurface() {
		if (terrainSurface == null) throw new IllegalStateException("Not a packed terrain result");
		return terrainSurface.clone();
	}

	public TerrainJobIdentity identity() {
		return identity;
	}

	public double[] densities() {
		if (completeTerrain != null) throw new IllegalStateException("Complete terrain is not a density field");
		if (densities != null) return densities.clone();
		double[] copy = new double[densityCount()];
		for (int i = 0; i < copy.length; i++) copy[i] = densityAt(i);
		return copy;
	}

	public int densityCount() {
		if (completeTerrain != null) return completeTerrain.blockCount();
		return terrainCodes != null ? terrainCodes.length + terrainSurface.length : densities != null ? densities.length : floatDensities.length;
	}

	public double densityAt(int index) {
		if (completeTerrain != null) throw new IllegalStateException("Complete terrain is not a density field");
		if (terrainCodes != null) return index < terrainCodes.length ? terrainCodes[index] : terrainSurface[index - terrainCodes.length];
		return densities != null ? densities[index] : floatDensities[index];
	}

	public long clientComputeNanos() {
		return clientComputeNanos;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof TerrainDensityResult result) || !identity.equals(result.identity)
			|| clientComputeNanos != result.clientComputeNanos || densityCount() != result.densityCount()) return false;
		if (completeTerrain != null || result.completeTerrain != null) return Objects.equals(completeTerrain, result.completeTerrain);
		for (int i = 0; i < densityCount(); i++) {
			if (Double.doubleToLongBits(densityAt(i)) != Double.doubleToLongBits(result.densityAt(i))) return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int result = identity.hashCode();
		if (completeTerrain != null) return Objects.hash(identity, completeTerrain, clientComputeNanos);
		int valuesHash = 1;
		for (int i = 0; i < densityCount(); i++) valuesHash = 31 * valuesHash + Double.hashCode(densityAt(i));
		result = 31 * result + valuesHash;
		return 31 * result + Long.hashCode(clientComputeNanos);
	}
}
