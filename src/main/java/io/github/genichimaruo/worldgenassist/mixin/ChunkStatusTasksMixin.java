package io.github.genichimaruo.worldgenassist.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.genichimaruo.worldgenassist.server.NoiseStageDigestLogger;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import io.github.genichimaruo.worldgenassist.server.WorldgenStageMetrics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksMixin {
	@WrapMethod(method = "full")
	private static CompletableFuture<ChunkAccess> worldgenAssist$observeFull(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
		CompletableFuture<ChunkAccess> result = original.call(context, step, chunks, chunk);
		if (!Boolean.getBoolean("worldgen_assist.remote.diagnostics")) return result;
		return result.whenComplete((generated, error) -> {
			if (error == null) io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info(
				"[CAWG] chunk.full_ready chunk={},{} dimension={}", chunk.getPos().x(), chunk.getPos().z(),
				context.level().dimension().identifier());
		});
	}

	@Inject(method = "generateStructureStarts", at = @At("HEAD"))
	private static void worldgenAssist$observeGeneration(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
		CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		RemoteWorldgenManager.observeGeneration(context, chunk);
	}

	@WrapMethod(
		method = "buildTerrain(Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;"
	)
	private static CompletableFuture<ChunkAccess> worldgenAssist$measureNoise(
		WorldGenContext context,
		ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk,
		Operation<CompletableFuture<ChunkAccess>> original
	) {
		Supplier<CompletableFuture<ChunkAccess>> local = () -> original.call(context, step, chunks, chunk);
		Supplier<CompletableFuture<ChunkAccess>> operation = () -> RemoteWorldgenManager.generateNoiseOrFallback(
			context,
			step,
			chunks,
			chunk,
			local
		);
		if (NoiseStageDigestLogger.isEnabled()) {
			return WorldgenStageMetrics.NOISE.measureAndThen(chunk.getPos(), operation,
				generated -> NoiseStageDigestLogger.computeAndLog(generated, context.level().dimension().identifier()));
		}

		return WorldgenStageMetrics.NOISE.measure(chunk.getPos(), operation);
	}
}
