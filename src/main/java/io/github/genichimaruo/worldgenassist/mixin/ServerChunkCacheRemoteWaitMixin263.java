package io.github.genichimaruo.worldgenassist.mixin;

import java.util.function.BooleanSupplier;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import io.github.genichimaruo.worldgenassist.server.ReadyTerrainLookup;

/** Keep result handling live while the server synchronously waits for a chunk. */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheRemoteWaitMixin263 implements ReadyTerrainLookup {
	@Invoker("getVisibleChunkIfPresent")
	protected abstract ChunkHolder worldgenAssist$visibleChunk(long key);
	@Override public boolean worldgenAssist$terrainReady(int x,int z) {
		ChunkHolder holder=worldgenAssist$visibleChunk(ChunkPos.pack(x,z));
		return holder!=null && holder.getChunkIfPresentUnchecked(ChunkStatus.TERRAIN)!=null;
	}
	@WrapOperation(
		method = {
			"getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
			"getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"
		},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"),
		require = 2
	)
	private void worldgenAssist$runRemoteCompletions(@Coerce Object executor, BooleanSupplier completed, Operation<Void> original) {
		if (completed.getAsBoolean()) {
			original.call(executor, completed);
			return;
		}
		RemoteWorldgenManager.duringSynchronousChunkWait(() -> original.call(executor, completed));
	}
}
