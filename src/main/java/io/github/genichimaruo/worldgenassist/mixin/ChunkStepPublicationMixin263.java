package io.github.genichimaruo.worldgenassist.mixin;

import java.util.concurrent.CompletableFuture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.genichimaruo.worldgenassist.server.FeatureStageDispatcher;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

/** Captures original status publication before a fast owned task can complete. */
@Mixin(ChunkStep.class)
abstract class ChunkStepPublicationMixin263 {
	@WrapMethod(method = "apply(Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;")
	private CompletableFuture<ChunkAccess> worldgenAssist$publishOwnedStage(WorldGenContext context,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
		return FeatureStageDispatcher.publish(context, (ChunkStep)(Object)this, chunk,
			() -> original.call(context, chunks, chunk));
	}
}
