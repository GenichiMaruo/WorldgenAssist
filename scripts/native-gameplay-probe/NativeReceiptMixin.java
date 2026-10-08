package io.github.genichimaruo.worldgenassist.probe.mixin;

import io.github.genichimaruo.worldgenassist.probe.NativeReceiptProbe;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class NativeReceiptMixin {
    @Inject(method="handleSystemChat",at=@At("TAIL"),require=1)
    private void systemMarker(ClientboundSystemChatPacket packet,CallbackInfo callback){NativeReceiptProbe.marker(packet.content().getString());}
    @Inject(method="handleDisguisedChat",at=@At("TAIL"),require=1)
    private void consoleMarker(ClientboundDisguisedChatPacket packet,CallbackInfo callback){NativeReceiptProbe.marker(packet.message().getString());}
    @Inject(method="handleLevelChunkWithLight",at=@At("TAIL"),require=1)
    private void receivedChunk(ClientboundLevelChunkWithLightPacket packet,CallbackInfo callback){NativeReceiptProbe.chunk(packet.x(),packet.z());}
    @Inject(method="handleRespawn",at=@At("TAIL"),require=1)
    private void respawnObserved(ClientboundRespawnPacket packet,CallbackInfo callback){NativeReceiptProbe.dimensionChanged();}
}
