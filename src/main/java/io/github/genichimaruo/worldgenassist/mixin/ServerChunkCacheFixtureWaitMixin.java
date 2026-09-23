package io.github.genichimaruo.worldgenassist.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerChunkCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.genichimaruo.worldgenassist.server.SeededLeafFixtureManager;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheFixtureWaitMixin {
	@WrapOperation(
		method = {
			"getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
			"getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"
		},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"),
		require = 2
	)
	private void worldgenAssist$localDuringSynchronousWait(@Coerce Object executor, BooleanSupplier completed, Operation<Void> original) {
		if (completed.getAsBoolean()) { original.call(executor, completed); return; }
		RemoteWorldgenManager.duringSynchronousChunkWait(() ->
			SeededLeafFixtureManager.duringSynchronousChunkWait(() -> original.call(executor, completed)));
	}
}
