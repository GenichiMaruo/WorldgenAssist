package io.github.genichimaruo.worldgenassist.mixin;

import io.github.genichimaruo.worldgenassist.forge.ForgeClientInit;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ForgeClientPacketListenerMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void worldgenAssist$onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        ForgeClientInit.onLogin();
    }
}
