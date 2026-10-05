package io.github.genichimaruo.worldgenassist.mixin;

import io.github.genichimaruo.worldgenassist.server.FeatureFixture263;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerMoveFeatureFixtureMixin263 {
	@Shadow public ServerPlayer player;
	@Inject(method = "handlePlayerPositionChange(DDDFFZZ)V", at = @At("HEAD"), cancellable = true)
	private void worldgenAssist$holdFixtureMovement(CallbackInfo callback) {
		// Both movement packets AND teleport acknowledgements reach this private collision helper.
		// Original callers already retain thread handoff and invalid-value/teleport-id checks.
		// Keep teleport acknowledgement, connection/keepalive/context/result handlers running.
		if (FeatureFixture263.suspendPlayer(player)) callback.cancel();
	}
}
