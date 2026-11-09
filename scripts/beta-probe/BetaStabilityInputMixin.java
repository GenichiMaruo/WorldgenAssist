package io.github.genichimaruo.worldgenassist.probe.mixin;
import io.github.genichimaruo.worldgenassist.probe.BetaStabilityProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LocalPlayer.class)
public abstract class BetaStabilityInputMixin {
    @Inject(method="aiStep",at=@At("HEAD"),require=1)
    private void betaInput(CallbackInfo ci){BetaStabilityProbe.holdInput(Minecraft.getInstance());}
}
