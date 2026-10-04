package io.github.genichimaruo.worldgenassist.mixin;

import java.util.concurrent.CompletableFuture;
import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe Minecraft's existing terrain request and its later completed load. */
@Mixin(ChunkMap.class)
abstract class ChunkMapLoadOpportunityMixin263 {
	@Shadow @Final private WorldGenContext worldGenContext;
	@Inject(method="scheduleGenerationTask(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/server/level/ChunkGenerationTask;",
		at=@At("RETURN"))
	private void worldgenAssist$observeTerrainRequest(ChunkStatus status, ChunkPos pos,
		CallbackInfoReturnable<ChunkGenerationTask> callback) {
		if (!RemoteWorldgenManager.observesChunkLoads()) return;
		try { RemoteWorldgenManager.observeTerrainRequested(worldGenContext,status,callback.getReturnValue()); }
		catch(RuntimeException error) {WorldgenAssist.LOGGER.warn("[CAWG] task.prefetch_observation_failed chunk={}",pos,error);}
	}
	@Inject(method="scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
		at=@At("RETURN"),cancellable=true)
	private void worldgenAssist$observeLoad(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		if (!RemoteWorldgenManager.observesChunkLoads()) return;
		callback.setReturnValue(callback.getReturnValue().thenApply(chunk -> {
			try { RemoteWorldgenManager.observeChunkLoaded(worldGenContext,chunk); }
			catch(RuntimeException error) {WorldgenAssist.LOGGER.warn("[CAWG] load.prefetch_observation_failed chunk={}",pos,error);}
			return chunk;
		}));
	}
}
