package io.github.genichimaruo.worldgenassist.mixin;

import io.github.genichimaruo.worldgenassist.server.FeatureFixture263;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
abstract class CommandsFeatureFixtureMixin263 {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void worldgenAssist$registerFixture(CallbackInfo callback) {
		FeatureFixture263.registerCommand(((Commands)(Object)this).getDispatcher());
	}
}
