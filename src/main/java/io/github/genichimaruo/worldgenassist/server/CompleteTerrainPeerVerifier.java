package io.github.genichimaruo.worldgenassist.server;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainWorkKind;

/** Full immutable comparison, distinct server-issued assignments; no world access or blocking wait. */
final class CompleteTerrainPeerVerifier {
	private CompleteTerrainPeerVerifier() { }
	static CompletableFuture<TerrainDensityResult> agree(RemoteJobCoordinator.Submission primary,
		RemoteJobCoordinator.Submission peer, BooleanSupplier current, Runnable applied,
		Consumer<RemoteDensityValidator.RemoteDensityValidationException> mismatch) {
		var a = primary.job(); var b = peer.job();
		if (primary.ownerId().equals(peer.ownerId()) || a.identity().jobId().equals(b.identity().jobId())
			|| a.workKind() != TerrainWorkKind.COMPLETE_TERRAIN || b.workKind() != a.workKind()
			|| !a.identity().dimension().equals(b.identity().dimension())
			|| !a.identity().chunkPos().equals(b.identity().chunkPos())
			|| !a.identity().contextFingerprint().equals(b.identity().contextFingerprint())
			|| !a.identity().protocolVersion().equals(b.identity().protocolVersion())
			|| a.worldSeed() != b.worldSeed() || a.generateStructures() != b.generateStructures()
			|| !a.shaping().equals(b.shaping())
			|| !a.noiseSettings().equals(b.noiseSettings()) || a.minY() != b.minY() || a.height() != b.height()
			|| a.cellWidth() != b.cellWidth() || a.cellHeight() != b.cellHeight()) {
			throw new IllegalArgumentException("Peer assignments are not independent matching terrain work");
		}
		CompletableFuture<TerrainDensityResult> agreed = new CompletableFuture<>();
		primary.result().whenComplete((value, error) -> { if (error != null) agreed.completeExceptionally(error); });
		peer.result().whenComplete((value, error) -> { if (error != null) agreed.completeExceptionally(error); });
		primary.result().thenCombine(peer.result(), (first, second) -> {
			if (agreed.isDone() || !current.getAsBoolean()) throw new CancellationException("Peer context expired");
			try {
				RemoteDensityValidator.Prepared.completeTerrain(a, null, 0).compare(first);
				RemoteDensityValidator.Prepared.completeTerrain(b, null, 0).compare(second);
				if (!first.completeTerrain().equals(second.completeTerrain())) {
					throw new RemoteDensityValidator.RemoteDensityValidationException("Independent peer whole-terrain mismatch: "
						+ CompleteTerrainDifference.describe(first.completeTerrain(),second.completeTerrain()));
				}
				return first.withLocalApproval(current, applied);
			} catch (RemoteDensityValidator.RemoteDensityValidationException error) {
				io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.warn(
					"[CAWG] job.peer_terrain_difference primary={} peer={} chunk={},{} reason={}",
					a.identity().jobId(),b.identity().jobId(),a.identity().chunkX(),a.identity().chunkZ(),error.getMessage());
				mismatch.accept(error); throw error;
			}
		}).whenComplete((value, error) -> {
			if (error == null) agreed.complete(value); else agreed.completeExceptionally(error);
		});
		return agreed;
	}
}
