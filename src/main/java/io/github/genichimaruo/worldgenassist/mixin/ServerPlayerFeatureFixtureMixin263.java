package io.github.genichimaruo.worldgenassist.mixin;

import io.github.genichimaruo.worldgenassist.server.FeatureFixture263;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freeze the two isolated fixture players too; ordinary tick freeze deliberately excludes players. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerFeatureFixtureMixin263 {
	@Inject(method = {"tick","doTick"}, at = @At("HEAD"), cancellable = true)
	private void worldgenAssist$holdFixturePhysics(CallbackInfo callback) {
		if (FeatureFixture263.suspendPlayer((ServerPlayer)(Object)this)) callback.cancel();
	}
}
