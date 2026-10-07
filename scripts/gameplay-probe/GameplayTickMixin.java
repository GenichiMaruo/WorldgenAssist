package io.github.genichimaruo.worldgenassist.probe.mixin;
import io.github.genichimaruo.worldgenassist.probe.GameplayProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class GameplayTickMixin {
    @Inject(method="tick",at=@At("TAIL"),require=1)
    private void ownedProbeTick(CallbackInfo callback){GameplayProbe.tick((Minecraft)(Object)this);}
}
