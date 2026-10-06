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
	@WrapMethod(method = "generateBiomes")
	private static CompletableFuture<ChunkAccess> worldgenAssist$reuseVerifiedBiomes(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
		var result = RemoteWorldgenManager.generateBiomesOrFallback(context, step, chunks, chunk,
			() -> original.call(context, step, chunks, chunk));
		return io.github.genichimaruo.worldgenassist.server.BiomeStageDigestLogger.enabled()
			? result.thenApply(generated -> { io.github.genichimaruo.worldgenassist.server.BiomeStageDigestLogger.log(generated); return generated; }) : result;
	}
	@Inject(method = "generateSpawn", at = @At("HEAD"))
	private static void worldgenAssist$observeFinalDecoration(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
		CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		if (!io.github.genichimaruo.worldgenassist.server.FeatureFixture263.captured(context,chunk))
			io.github.genichimaruo.worldgenassist.server.DecorationStageDigestLogger.log(context, chunk);
	}

	@WrapMethod(method = "generateSpawn")
	private static CompletableFuture<ChunkAccess> worldgenAssist$fixtureSpawn(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
		return io.github.genichimaruo.worldgenassist.server.FeatureFixture263.spawn(context,chunk,
			() -> original.call(context,step,chunks,chunk));
	}

	@WrapMethod(method = "generateFeatures")
	private static CompletableFuture<ChunkAccess> worldgenAssist$scheduleFeatures(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
		return io.github.genichimaruo.worldgenassist.server.FeatureStageDispatcher.generate(context, step, chunk,
			() -> original.call(context, step, chunks, chunk));
	}

	@WrapMethod(method = "initializeLight")
	private static CompletableFuture<ChunkAccess> worldgenAssist$ownLightInitialization(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
		return io.github.genichimaruo.worldgenassist.server.FeatureStageDispatcher.initializeLight(context, step, chunk,
			() -> original.call(context, step, chunks, chunk));
	}

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
		Supplier<CompletableFuture<ChunkAccess>> phased = io.github.genichimaruo.worldgenassist.server.FeatureFixture263.enabled()
			? () -> io.github.genichimaruo.worldgenassist.server.FeatureFixture263.terrain(context,chunk,operation) : operation;
		if (NoiseStageDigestLogger.isEnabled()) {
			return WorldgenStageMetrics.NOISE.measureAndThen(chunk.getPos(), phased,
				generated -> NoiseStageDigestLogger.computeAndLog(generated, context.level().dimension().identifier()));
		}

		return WorldgenStageMetrics.NOISE.measure(chunk.getPos(), phased);
	}
}
