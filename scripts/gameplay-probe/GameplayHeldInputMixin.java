package io.github.genichimaruo.worldgenassist.probe.mixin;
import io.github.genichimaruo.worldgenassist.probe.GameplayProbe;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MultiPlayerGameMode.class)
public abstract class GameplayHeldInputMixin {
    @Inject(method="stopDestroyBlock",at=@At("HEAD"),cancellable=true,require=1)
    private void keepOnlyOwnedVirtualAttackHeld(CallbackInfo callback){if(GameplayProbe.holdsMining((MultiPlayerGameMode)(Object)this))callback.cancel();}
}
