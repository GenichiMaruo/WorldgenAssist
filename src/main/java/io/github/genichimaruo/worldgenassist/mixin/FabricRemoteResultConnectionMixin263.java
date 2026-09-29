package io.github.genichimaruo.worldgenassist.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import io.github.genichimaruo.worldgenassist.server.FabricRemoteResultIngress;

@Mixin(Connection.class)
abstract class FabricRemoteResultConnectionMixin263 {
	@Shadow private int receivedPackets;
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"), cancellable = true)
	private void worldgenAssist$receiveResult(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		if (FabricRemoteResultIngress.receive((Connection)(Object)this, packet)) {
			receivedPackets++;
			ci.cancel();
		}
	}
	@Inject(method = "channelInactive", at = @At("HEAD"))
	private void worldgenAssist$closeSession(ChannelHandlerContext context, CallbackInfo ci) {
		FabricRemoteResultIngress.remove((Connection)(Object)this);
	}
	@Inject(method = "setupInboundProtocol", at = @At("HEAD"))
	private void worldgenAssist$changeProtocol(ProtocolInfo<?> protocol, PacketListener listener, CallbackInfo ci) {
		FabricRemoteResultIngress.remove((Connection)(Object)this);
	}
}
