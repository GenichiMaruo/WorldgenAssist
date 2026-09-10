package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;

/**
 * One-shot server admission for an unregistered seeded-leaf result. This gate
 * authenticates before decompression and does not replace later authoritative
 * density sampling.
 */
public final class SeededLeafDensityResultGate {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	private SeededLeafDensityResultGate() {
	}

	public static GateResult accept(
		SeededLeafJobAuthority authority,
		UUID ownerId,
		SeededLeafDensityResultEnvelope envelope
	) {
		ClaimGateResult claimed = claimBeforeDecode(authority, ownerId, envelope);
		if (claimed.status() == ClaimGateStatus.CLAIM_REJECTED) {
			return GateResult.rejected(Status.CLAIM_REJECTED, claimed.claimStatus());
		}
		if (claimed.status() == ClaimGateStatus.SHAPE_MISMATCH) {
			return GateResult.rejected(Status.SHAPE_MISMATCH, claimed.claimStatus());
		}
		return decodeClaimed(claimed.claimedResult().orElseThrow());
	}

	/** Performs the cheap owner/claim/shape checks and consumes a valid claim before queueing decode work. */
	public static ClaimGateResult claimBeforeDecode(
		SeededLeafJobAuthority authority,
		UUID ownerId,
		SeededLeafDensityResultEnvelope envelope
	) {
		Objects.requireNonNull(authority, "authority");
		Objects.requireNonNull(envelope, "envelope");
		SeededLeafJobAuthority.ClaimResult claim = authority.claim(ownerId, envelope.claim());
		if (claim.status() != SeededLeafJobAuthority.ClaimStatus.ACCEPTED) {
			return ClaimGateResult.rejected(ClaimGateStatus.CLAIM_REJECTED, claim.status());
		}

		AuthorizedSeededLeafJob authorization = claim.authorization().orElseThrow();
		SeededLeafJob job = authorization.job();
		if (envelope.densityCount() != job.sampleCount()) {
			return ClaimGateResult.rejected(ClaimGateStatus.SHAPE_MISMATCH, claim.status());
		}
		return ClaimGateResult.ready(
			new ClaimedResult(JobGeometry.from(job), envelope, claim.contextGeneration()),
			claim.status()
		);
	}

	/** Performs bounded decompression and value validation for an already consumed claim. */
	public static GateResult decodeClaimed(ClaimedResult claimed) {
		Objects.requireNonNull(claimed, "claimed");
		SeededLeafDensityResult result;
		try {
			result = claimed.envelope().decode();
		} catch (IllegalArgumentException exception) {
			return GateResult.rejected(Status.INVALID_DENSITY_DATA, SeededLeafJobAuthority.ClaimStatus.ACCEPTED);
		}
		return GateResult.accepted(
			AcceptedResult.from(claimed.geometry(), result),
			SeededLeafJobAuthority.ClaimStatus.ACCEPTED
		);
	}

	public enum ClaimGateStatus {
		READY_FOR_DECODE,
		CLAIM_REJECTED,
		SHAPE_MISMATCH
	}

	public record ClaimGateResult(
		ClaimGateStatus status,
		SeededLeafJobAuthority.ClaimStatus claimStatus,
		Optional<ClaimedResult> claimedResult
	) {
		public ClaimGateResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(claimStatus, "claimStatus");
			Objects.requireNonNull(claimedResult, "claimedResult");
			if ((status == ClaimGateStatus.READY_FOR_DECODE) != claimedResult.isPresent()) {
				throw new IllegalArgumentException("Only a ready claim carries deferred decode work");
			}
		}

		private static ClaimGateResult ready(ClaimedResult result, SeededLeafJobAuthority.ClaimStatus claimStatus) {
			return new ClaimGateResult(ClaimGateStatus.READY_FOR_DECODE, claimStatus, Optional.of(result));
		}

		private static ClaimGateResult rejected(ClaimGateStatus status, SeededLeafJobAuthority.ClaimStatus claimStatus) {
			return new ClaimGateResult(status, claimStatus, Optional.empty());
		}
	}

	/** Deferred work contains no transcript or server authenticator. */
	public record ClaimedResult(
		JobGeometry geometry,
		SeededLeafDensityResultEnvelope envelope,
		long authorityGeneration
	) {
		public ClaimedResult {
			Objects.requireNonNull(geometry, "geometry");
			Objects.requireNonNull(envelope, "envelope");
			if (authorityGeneration < 1L) {
				throw new IllegalArgumentException("Claimed result authority generation must be positive");
			}
			if (!geometry.jobId().equals(envelope.claim().jobId())
				|| !geometry.contextId().equals(envelope.claim().contextId())) {
				throw new IllegalArgumentException("Claimed result identity does not match its geometry");
			}
			if (envelope.densityCount() != geometry.sampleCount()) {
				throw new IllegalArgumentException("Claimed result count does not match its geometry");
			}
		}
	}

	/** Seed-free projection of the server-retained job, excluding its transcript and authentication key. */
	public record JobGeometry(
		UUID jobId,
		OpaqueWorldgenContextId contextId,
		Identifier dimension,
		int chunkX,
		int chunkZ,
		Identifier noiseSettings,
		int minY,
		int height,
		int cellWidth,
		int cellHeight
	) {
		private static JobGeometry from(SeededLeafJob job) {
			return new JobGeometry(
				job.jobId(), job.contextId(), job.dimension(), job.chunkX(), job.chunkZ(), job.noiseSettings(),
				job.minY(), job.height(), job.cellWidth(), job.cellHeight()
			);
		}

		public JobGeometry {
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(contextId, "contextId");
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(noiseSettings, "noiseSettings");
			validateGeometry(dimension, chunkX, chunkZ, noiseSettings, minY, height, cellWidth, cellHeight);
		}

		public int sampleCount() {
			return Math.multiplyExact(16 * 16, height);
		}
	}

	public enum Status {
		ACCEPTED_FOR_AUTHORITATIVE_VALIDATION,
		CLAIM_REJECTED,
		SHAPE_MISMATCH,
		INVALID_DENSITY_DATA
	}

	public record GateResult(
		Status status,
		SeededLeafJobAuthority.ClaimStatus claimStatus,
		Optional<AcceptedResult> acceptedResult
	) {
		public GateResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(claimStatus, "claimStatus");
			Objects.requireNonNull(acceptedResult, "acceptedResult");
			if ((status == Status.ACCEPTED_FOR_AUTHORITATIVE_VALIDATION) != acceptedResult.isPresent()) {
				throw new IllegalArgumentException("Only accepted gate results carry density data");
			}
		}

		private static GateResult accepted(AcceptedResult result, SeededLeafJobAuthority.ClaimStatus claimStatus) {
			return new GateResult(Status.ACCEPTED_FOR_AUTHORITATIVE_VALIDATION, claimStatus, Optional.of(result));
		}

		private static GateResult rejected(Status status, SeededLeafJobAuthority.ClaimStatus claimStatus) {
			return new GateResult(status, claimStatus, Optional.empty());
		}
	}

	/** Seed-free geometry projection; the transcript is not retained past admission. */
	public record AcceptedResult(
		UUID jobId,
		OpaqueWorldgenContextId contextId,
		Identifier dimension,
		int chunkX,
		int chunkZ,
		Identifier noiseSettings,
		int minY,
		int height,
		int cellWidth,
		int cellHeight,
		SeededLeafDensityResult densityResult
	) {
		private static AcceptedResult from(JobGeometry geometry, SeededLeafDensityResult result) {
			return new AcceptedResult(
				geometry.jobId(),
				geometry.contextId(),
				geometry.dimension(),
				geometry.chunkX(),
				geometry.chunkZ(),
				geometry.noiseSettings(),
				geometry.minY(),
				geometry.height(),
				geometry.cellWidth(),
				geometry.cellHeight(),
				result
			);
		}

		public AcceptedResult {
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(contextId, "contextId");
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(noiseSettings, "noiseSettings");
			Objects.requireNonNull(densityResult, "densityResult");
			validateGeometry(dimension, chunkX, chunkZ, noiseSettings, minY, height, cellWidth, cellHeight);
			if (!jobId.equals(densityResult.claim().jobId())
				|| !contextId.equals(densityResult.claim().contextId())) {
				throw new IllegalArgumentException("Accepted density result identity does not match its geometry");
			}
			if (densityResult.densityCount() != Math.multiplyExact(TerrainDensityJob.CHUNK_SIDE * TerrainDensityJob.CHUNK_SIDE, height)) {
				throw new IllegalArgumentException("Accepted density count does not match its geometry");
			}
		}

		public int densityCount() {
			return densityResult.densityCount();
		}
	}

	private static void validateGeometry(
		Identifier dimension,
		int chunkX,
		int chunkZ,
		Identifier noiseSettings,
		int minY,
		int height,
		int cellWidth,
		int cellHeight
	) {
		if (!OVERWORLD.equals(dimension) || !OVERWORLD.equals(noiseSettings)) {
			throw new IllegalArgumentException("Seeded-leaf geometry currently supports only minecraft:overworld");
		}
		if (!ChunkPos.isValid(chunkX, chunkZ)) {
			throw new IllegalArgumentException("Chunk coordinate is outside Minecraft generation bounds");
		}
		if (height < 1 || height > TerrainDensityJob.MAX_HEIGHT) {
			throw new IllegalArgumentException("Invalid seeded-leaf generation height: " + height);
		}
		if (cellWidth < 1 || cellWidth > TerrainDensityJob.CHUNK_SIDE
			|| TerrainDensityJob.CHUNK_SIDE % cellWidth != 0) {
			throw new IllegalArgumentException("Invalid seeded-leaf cell width: " + cellWidth);
		}
		if (cellHeight < 1 || cellHeight > height || height % cellHeight != 0
			|| Math.floorMod(minY, cellHeight) != 0) {
			throw new IllegalArgumentException("Invalid seeded-leaf vertical cell geometry");
		}
		Math.addExact(minY, height);
	}
}
