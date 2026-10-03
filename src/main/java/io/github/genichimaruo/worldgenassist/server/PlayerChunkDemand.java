package io.github.genichimaruo.worldgenassist.server;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Immutable server-thread snapshot; contains no live player/world objects. */
public record PlayerChunkDemand(UUID ownerId, Identifier dimension, int chunkX, int chunkZ, int viewDistance) {
	public PlayerChunkDemand {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(dimension, "dimension");
		if (viewDistance < 2 || viewDistance > 32) { throw new IllegalArgumentException("Invalid view distance"); }
	}

	public boolean includes(Identifier requestedDimension, int x, int z) {
		return dimension.equals(requestedDimension)
			&& ChunkTrackingView.isInViewDistance(chunkX, chunkZ, viewDistance, x, z);
	}

	/** Ticket propagation also requires terrain outside the circular delivery view.
	 * Used only for chunks whose generation Minecraft already requested. */
	public boolean includesGeneration(Identifier requestedDimension, int x, int z) {
		int margin = ChunkLevel.byStatus(ChunkStatus.TERRAIN) - ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
		long radius = (long)viewDistance + margin;
		return dimension.equals(requestedDimension) && Math.abs((long)x - chunkX) <= radius
			&& Math.abs((long)z - chunkZ) <= radius;
	}

	public static Optional<PlayerChunkDemand> selectGeneration(List<PlayerChunkDemand> demands, Identifier dimension, int x, int z) {
		return demands.stream().filter(demand -> demand.includesGeneration(dimension, x, z))
			.min(Comparator.comparingLong((PlayerChunkDemand demand) -> demand.distanceSquared(x, z))
				.thenComparing(PlayerChunkDemand::ownerId));
	}

	private long distanceSquared(int x, int z) {
		long dx = (long)x - chunkX;
		long dz = (long)z - chunkZ;
		return dx * dx + dz * dz;
	}

	public static Optional<PlayerChunkDemand> select(List<PlayerChunkDemand> demands, Identifier dimension, int x, int z) {
		return demands.stream().filter(demand -> demand.includes(dimension, x, z))
			.min(Comparator.comparingLong((PlayerChunkDemand demand) -> demand.distanceSquared(x, z))
				.thenComparing(PlayerChunkDemand::ownerId));
	}
}
