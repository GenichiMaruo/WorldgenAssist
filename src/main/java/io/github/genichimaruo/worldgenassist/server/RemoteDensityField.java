package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.UUID;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;

public final class RemoteDensityField {
	private final UUID jobId;
	private final int chunkX;
	private final int chunkZ;
	private final int minY;
	private final int height;
	private final int cellWidth;
	private final int cellHeight;
	private final double[] densities;

	public RemoteDensityField(TerrainDensityJob job, TerrainDensityResult result) {
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
	}

	private RemoteDensityField(SeededLeafDensityResultGate.AcceptedResult accepted) {
		Objects.requireNonNull(accepted, "accepted");
		jobId = accepted.jobId();
		chunkX = accepted.chunkX();
		chunkZ = accepted.chunkZ();
		minY = accepted.minY();
		height = accepted.height();
		cellWidth = accepted.cellWidth();
		cellHeight = accepted.cellHeight();
		densities = accepted.densityResult().densities();
	}

	/** Constructs an installable field only from the executor's authoritative validation success. */
	public static RemoteDensityField fromValidated(
		SeededLeafResultValidationExecutor.Outcome outcome,
		SeededLeafJobAuthority authority
	) {
		Objects.requireNonNull(outcome, "outcome");
		Objects.requireNonNull(authority, "authority");
		if (outcome.status() != SeededLeafResultValidationExecutor.Status.VALIDATED) {
			throw new IllegalArgumentException("Only a validated seeded-leaf outcome can become a remote density field");
		}
		synchronized (authority) {
			SeededLeafDensityResultGate.AcceptedResult accepted = outcome.acceptedResult().orElseThrow();
			if (outcome.authorityGeneration() != authority.contextGeneration()
				|| authority.contextId().filter(accepted.contextId()::equals).isEmpty()) {
				throw new IllegalStateException("Validated seeded-leaf outcome belongs to a stale authority context");
			}
			return new RemoteDensityField(accepted);
		}
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
