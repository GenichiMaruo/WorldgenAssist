package io.github.genichimaruo.worldgenassist.common;

import java.util.Arrays;
import java.util.Objects;

/** Seed-free density values returned for one authenticated seeded-leaf claim. */
public final class SeededLeafDensityResult {
	public static final double MAX_ABSOLUTE_DENSITY = TerrainDensityResult.MAX_ABSOLUTE_DENSITY;
	public static final long MAX_CLIENT_COMPUTE_NANOS = TerrainDensityResult.MAX_CLIENT_COMPUTE_NANOS;

	private final SeededLeafJobClaim claim;
	private final double[] densities;
	private final long clientComputeNanos;

	public SeededLeafDensityResult(SeededLeafJobClaim claim, double[] densities, long clientComputeNanos) {
		this.claim = Objects.requireNonNull(claim, "claim");
		Objects.requireNonNull(densities, "densities");
		if (densities.length < 1 || densities.length > TerrainDensityJob.MAX_SAMPLE_COUNT) {
			throw new IllegalArgumentException(
				"Density count must be between 1 and " + TerrainDensityJob.MAX_SAMPLE_COUNT + ": " + densities.length
			);
		}
		this.densities = densities.clone();
		for (int index = 0; index < this.densities.length; index++) {
			double density = this.densities[index];
			if (!Double.isFinite(density) || Math.abs(density) > MAX_ABSOLUTE_DENSITY) {
				throw new IllegalArgumentException("Invalid density at index " + index + ": " + density);
			}
		}
		if (clientComputeNanos < 0L || clientComputeNanos > MAX_CLIENT_COMPUTE_NANOS) {
			throw new IllegalArgumentException(
				"Client compute time must be between 0 and " + MAX_CLIENT_COMPUTE_NANOS
					+ " nanoseconds: " + clientComputeNanos
			);
		}
		this.clientComputeNanos = clientComputeNanos;
	}

	public SeededLeafJobClaim claim() {
		return claim;
	}

	public double[] densities() {
		return densities.clone();
	}

	public int densityCount() {
		return densities.length;
	}

	public double densityAt(int index) {
		return densities[index];
	}

	public long clientComputeNanos() {
		return clientComputeNanos;
	}

	@Override
	public boolean equals(Object other) {
		return this == other
			|| other instanceof SeededLeafDensityResult result
				&& claim.equals(result.claim)
				&& clientComputeNanos == result.clientComputeNanos
				&& Arrays.equals(densities, result.densities);
	}

	@Override
	public int hashCode() {
		int result = claim.hashCode();
		result = 31 * result + Arrays.hashCode(densities);
		return 31 * result + Long.hashCode(clientComputeNanos);
	}
}
