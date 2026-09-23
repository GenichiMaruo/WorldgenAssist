package io.github.genichimaruo.worldgenassist.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.genichimaruo.worldgenassist.server.LocalWorldgenTaskBackend;
import io.github.genichimaruo.worldgenassist.server.NoiseStageBackendConfig;
import io.github.genichimaruo.worldgenassist.server.NoiseTaskBenchmarkLogger;
import io.github.genichimaruo.worldgenassist.server.RemoteDensityField;
import io.github.genichimaruo.worldgenassist.server.RemoteDensityTarget;
import io.github.genichimaruo.worldgenassist.server.VanillaDelegatingWorldgenTaskBackend;
import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseBasedChunkGeneratorMixin {
	@WrapOperation(
		method = "buildTerrain",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<ChunkAccess> worldgenAssist$selectNoiseExecutor(
		Supplier<ChunkAccess> task,
		Executor vanillaExecutor,
		Operation<CompletableFuture<ChunkAccess>> original,
		ChunkAccess centerChunk,
		Blender blender,
		RandomState randomState,
		StructureManager structureManager,
		BiomeManager biomeManager,
		WorldGenRegion carverBiomeRegion,
		Set<Holder<Biome>> possibleBiomes
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

	@WrapOperation(
		method = "doFill",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/densityfunction/DensitySampler$Bound;sampleVolume(Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;)Lnet/minecraft/world/level/levelgen/densityfunction/ScopedDensityBuffer;"
		)
	)
	private ScopedDensityBuffer worldgenAssist$sampleOrUseRemote(
		DensitySampler.Bound sampler,
		DensityVolume volume,
		Operation<ScopedDensityBuffer> original,
		NoiseChunk noiseChunk,
		ChunkAccess chunk
	) {
		RemoteDensityTarget target = (RemoteDensityTarget)chunk;
		RemoteDensityField field = target.worldgenAssist$getRemoteDensity();
		if (field == null) { return original.call(sampler, volume); }
		ScopedDensityBuffer buffer = sampler.context().acquireBuffer(volume);
		try {
			field.copyVolume(volume, buffer);
			return buffer;
		} catch (RuntimeException exception) {
			buffer.close();
			target.worldgenAssist$clearRemoteDensity(field.jobId());
			WorldgenAssist.LOGGER.warn("[CAWG] job.remote_volume_rejected id={} reason={}", field.jobId(), exception.toString());
			return original.call(sampler, volume);
		}
	}
}
