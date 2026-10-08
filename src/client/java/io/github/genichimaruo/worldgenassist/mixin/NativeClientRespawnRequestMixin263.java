package io.github.genichimaruo.worldgenassist.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import io.github.genichimaruo.worldgenassist.client.NativeClientRequestIngress;

@Mixin(ClientPacketListener.class)
abstract class NativeClientRespawnRequestMixin263 {
	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void worldgenAssist$resumeNativeRequests(ClientboundRespawnPacket packet, CallbackInfo ci) {
		NativeClientRequestIngress.resume(Minecraft.getInstance());
	}
}
