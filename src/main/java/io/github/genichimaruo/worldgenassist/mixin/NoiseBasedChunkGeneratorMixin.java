package io.github.genichimaruo.worldgenassist.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.genichimaruo.worldgenassist.server.LocalWorldgenTaskBackend;
import io.github.genichimaruo.worldgenassist.server.NoiseStageBackendConfig;
import io.github.genichimaruo.worldgenassist.server.NoiseTaskBenchmarkLogger;
import io.github.genichimaruo.worldgenassist.server.VanillaDelegatingWorldgenTaskBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseBasedChunkGeneratorMixin {
	@WrapOperation(
		method = "fillFromNoise(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<ChunkAccess> worldgenAssist$selectNoiseExecutor(
		Supplier<ChunkAccess> task,
		Executor vanillaExecutor,
		Operation<CompletableFuture<ChunkAccess>> original,
		Blender blender,
		RandomState randomState,
		StructureManager structureManager,
		ChunkAccess centerChunk
	) {
		NoiseStageBackendConfig backendConfig = NoiseStageBackendConfig.current();
		Supplier<ChunkAccess> selectedTask = NoiseTaskBenchmarkLogger.wrap(
			centerChunk.getPos(),
			backendConfig.mode().id(),
			task
		);
		if (backendConfig.mode() == NoiseStageBackendConfig.Mode.VANILLA) {
			return original.call(selectedTask, vanillaExecutor);
		}
		if (backendConfig.mode() == NoiseStageBackendConfig.Mode.DELEGATE) {
			Executor delegatedExecutor = VanillaDelegatingWorldgenTaskBackend.instance().executorFor(
				"noise",
				centerChunk.getPos(),
				vanillaExecutor
			);
			return original.call(selectedTask, delegatedExecutor);
		}

		Executor localExecutor = LocalWorldgenTaskBackend.instance().executorFor(
			"noise",
			centerChunk.getPos(),
			vanillaExecutor
		);
		return original.call(selectedTask, localExecutor);
	}
}
