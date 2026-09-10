package io.github.genichimaruo.worldgenassist.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

public final class PlayerOwnedChunkPredictor {
	static final int MIN_EFFECTIVE_VIEW_DISTANCE = 2;
	static final int MAX_EFFECTIVE_VIEW_DISTANCE = 32;
	static final int MAX_TRACKED_MOVEMENT_CHUNKS = 4;
	static final int MAX_UNLOADED_SCAN_CHUNKS = 8;

	private final Map<UUID, Observation> observations = new HashMap<>();

	public synchronized Optional<Prediction> observe(
		UUID ownerId,
		Identifier dimension,
		int chunkX,
		int chunkZ,
		int effectiveViewDistance,
		int leadChunks
	) {
		requireOwner(ownerId);
		Objects.requireNonNull(dimension, "dimension");
		if (!ChunkPos.isValid(chunkX, chunkZ)) {
			throw new IllegalArgumentException("Observed chunk is outside Minecraft's valid range: " + chunkX + "," + chunkZ);
		}
		if (effectiveViewDistance < MIN_EFFECTIVE_VIEW_DISTANCE || effectiveViewDistance > MAX_EFFECTIVE_VIEW_DISTANCE) {
			throw new IllegalArgumentException("Effective view distance must be between 2 and 32: " + effectiveViewDistance);
		}
		if (leadChunks < 1 || leadChunks > RemoteWorldgenConfig.MAX_PREDICTION_LEAD_CHUNKS) {
			throw new IllegalArgumentException(
				"Prediction lead must be between 1 and " + RemoteWorldgenConfig.MAX_PREDICTION_LEAD_CHUNKS + ": " + leadChunks
			);
		}

		Observation previous = observations.get(ownerId);
		if (previous == null || !previous.dimension.equals(dimension)) {
			observations.put(ownerId, new Observation(dimension, chunkX, chunkZ, 0, 0));
			return Optional.empty();
		}

		long movedX = (long)chunkX - previous.chunkX;
		long movedZ = (long)chunkZ - previous.chunkZ;
		int directionX = previous.directionX;
		int directionZ = previous.directionZ;
		if (movedX != 0L || movedZ != 0L) {
			if (Math.max(Math.abs(movedX), Math.abs(movedZ)) > MAX_TRACKED_MOVEMENT_CHUNKS) {
				observations.put(ownerId, new Observation(dimension, chunkX, chunkZ, 0, 0));
				return Optional.empty();
			}
			directionX = Long.signum(movedX);
			directionZ = Long.signum(movedZ);
			observations.put(ownerId, new Observation(dimension, chunkX, chunkZ, directionX, directionZ));
		}
		if (directionX == 0 && directionZ == 0) {
			return Optional.empty();
		}

		long distance = (long)effectiveViewDistance + leadChunks;
		long predictedX = chunkX + directionX * distance;
		long predictedZ = chunkZ + directionZ * distance;
		if (predictedX < Integer.MIN_VALUE || predictedX > Integer.MAX_VALUE
			|| predictedZ < Integer.MIN_VALUE || predictedZ > Integer.MAX_VALUE
			|| !ChunkPos.isValid((int)predictedX, (int)predictedZ)) {
			return Optional.empty();
		}
		return Optional.of(new Prediction(
			ownerId,
			dimension,
			(int)predictedX,
			(int)predictedZ,
			directionX,
			directionZ,
			effectiveViewDistance
		));
	}

	public synchronized void remove(UUID ownerId) {
		requireOwner(ownerId);
		observations.remove(ownerId);
	}

	public synchronized void clear() {
		observations.clear();
	}

	public synchronized int trackedPlayers() {
		return observations.size();
	}

	public static int effectiveViewDistance(int requestedViewDistance, int serverViewDistance) {
		return Math.clamp(
			Math.min(requestedViewDistance, serverViewDistance),
			MIN_EFFECTIVE_VIEW_DISTANCE,
			MAX_EFFECTIVE_VIEW_DISTANCE
		);
	}

	public static Optional<Prediction> advance(Prediction prediction, int additionalChunks) {
		Objects.requireNonNull(prediction, "prediction");
		if (additionalChunks < 0 || additionalChunks > MAX_UNLOADED_SCAN_CHUNKS) {
			throw new IllegalArgumentException(
				"Additional prediction scan must be between 0 and " + MAX_UNLOADED_SCAN_CHUNKS + ": " + additionalChunks
			);
		}
		long chunkX = (long)prediction.chunkX + (long)prediction.directionX * additionalChunks;
		long chunkZ = (long)prediction.chunkZ + (long)prediction.directionZ * additionalChunks;
		if (chunkX < Integer.MIN_VALUE || chunkX > Integer.MAX_VALUE
			|| chunkZ < Integer.MIN_VALUE || chunkZ > Integer.MAX_VALUE
			|| !ChunkPos.isValid((int)chunkX, (int)chunkZ)) {
			return Optional.empty();
		}
		return Optional.of(new Prediction(
			prediction.ownerId,
			prediction.dimension,
			(int)chunkX,
			(int)chunkZ,
			prediction.directionX,
			prediction.directionZ,
			prediction.effectiveViewDistance
		));
	}

	private static void requireOwner(UUID ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		if (ownerId.getMostSignificantBits() == 0L && ownerId.getLeastSignificantBits() == 0L) {
			throw new IllegalArgumentException("ownerId must not be the all-zero UUID");
		}
	}

	private record Observation(Identifier dimension, int chunkX, int chunkZ, int directionX, int directionZ) {
	}

	public record Prediction(
		UUID ownerId,
		Identifier dimension,
		int chunkX,
		int chunkZ,
		int directionX,
		int directionZ,
		int effectiveViewDistance
	) {
		public Prediction {
			requireOwner(ownerId);
			Objects.requireNonNull(dimension, "dimension");
			if (!ChunkPos.isValid(chunkX, chunkZ)) {
				throw new IllegalArgumentException("Predicted chunk is outside Minecraft's valid range: " + chunkX + "," + chunkZ);
			}
			if (directionX < -1 || directionX > 1 || directionZ < -1 || directionZ > 1
				|| directionX == 0 && directionZ == 0) {
				throw new IllegalArgumentException("Prediction direction must be a non-zero normalized chunk vector");
			}
			if (effectiveViewDistance < MIN_EFFECTIVE_VIEW_DISTANCE || effectiveViewDistance > MAX_EFFECTIVE_VIEW_DISTANCE) {
				throw new IllegalArgumentException("Effective view distance must be between 2 and 32: " + effectiveViewDistance);
			}
		}
	}
}
