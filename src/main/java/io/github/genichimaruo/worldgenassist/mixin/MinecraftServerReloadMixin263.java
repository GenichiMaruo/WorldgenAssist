package io.github.genichimaruo.worldgenassist.mixin;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Invalidate remote jobs before the server begins replacing worldgen registries. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerReloadMixin263 {
	@Inject(method = "reloadResources", at = @At("HEAD"))
	private void worldgenAssist$beforeReload(Collection<String> packs,
		CallbackInfoReturnable<CompletableFuture<Void>> callback) {
		RemoteWorldgenManager.onReloadStart();
	}
}
