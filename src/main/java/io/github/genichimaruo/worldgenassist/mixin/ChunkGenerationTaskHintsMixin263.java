package io.github.genichimaruo.worldgenassist.mixin;

import java.util.function.Consumer;
import io.github.genichimaruo.worldgenassist.server.TerrainTaskHintSource;
import io.github.genichimaruo.worldgenassist.server.TerrainTaskHints;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChunkGenerationTask.class)
abstract class ChunkGenerationTaskHintsMixin263 implements TerrainTaskHintSource {
	@Shadow @Final private ChunkPos pos;
	@Shadow @Final private StaticCache2D<GenerationChunkHolder> cache;
	@Shadow @Final public ChunkStatus targetStatus;
	@Shadow private volatile boolean markedForCancellation;
	@Override public void worldgenAssist$visitTerrainCandidates(Consumer<GenerationChunkHolder> visitor) {
		TerrainTaskHints.visit(pos, targetStatus, cache, () -> markedForCancellation, visitor);
	}
}
