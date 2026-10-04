package io.github.genichimaruo.worldgenassist.server;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** The same accumulated TERRAIN layer radius used by ChunkGenerationTask. */
public final class TerrainTaskHints {
	private TerrainTaskHints() { }
	public static <T> void visit(ChunkPos center, ChunkStatus target, StaticCache2D<T> claims,
		BooleanSupplier cancelled, Consumer<T> visitor) {
		if (target.isBefore(ChunkStatus.TERRAIN) || cancelled.getAsBoolean()) return;
		int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(target).getAccumulatedRadiusOf(ChunkStatus.TERRAIN);
		for (int x = center.x() - radius; x <= center.x() + radius; x++) {
			for (int z = center.z() - radius; z <= center.z() + radius; z++) {
				if (cancelled.getAsBoolean()) return;
				visitor.accept(claims.get(x, z));
			}
		}
	}
}
