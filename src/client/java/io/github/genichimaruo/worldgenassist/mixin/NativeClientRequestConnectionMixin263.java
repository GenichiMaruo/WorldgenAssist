package io.github.genichimaruo.worldgenassist.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import io.github.genichimaruo.worldgenassist.client.NativeClientRequestIngress;

/** Observation only: native APIs still route every original packet and maintain counters. */
@Mixin(Connection.class)
abstract class NativeClientRequestConnectionMixin263 {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
	private void worldgenAssist$suspendBeforeRespawn(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ClientboundRespawnPacket) NativeClientRequestIngress.suspend((Connection)(Object)this);
	}
	@Inject(method = "channelInactive", at = @At("HEAD"))
	private void worldgenAssist$closeNativeRequests(ChannelHandlerContext context, CallbackInfo ci) {
		NativeClientRequestIngress.remove((Connection)(Object)this);
	}
	@Inject(method = "setupInboundProtocol", at = @At("HEAD"))
	private void worldgenAssist$changeNativeProtocol(ProtocolInfo<?> protocol, PacketListener listener, CallbackInfo ci) {
		NativeClientRequestIngress.remove((Connection)(Object)this);
	}
}
