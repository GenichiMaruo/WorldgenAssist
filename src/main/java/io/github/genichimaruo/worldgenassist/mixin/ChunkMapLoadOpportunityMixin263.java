package io.github.genichimaruo.worldgenassist.mixin;

import java.util.concurrent.CompletableFuture;
import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe Minecraft's own completed load before it schedules structure/biome work. */
@Mixin(ChunkMap.class)
abstract class ChunkMapLoadOpportunityMixin263 {
	@Shadow @Final private WorldGenContext worldGenContext;
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
