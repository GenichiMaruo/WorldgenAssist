package io.github.genichimaruo.worldgenassist.mixin;

import io.github.genichimaruo.worldgenassist.forge.ForgeClientInit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ForgeMinecraftDisconnectMixin {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void worldgenAssist$onDisconnect(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
        ForgeClientInit.onDisconnect();
    }
}
