package io.github.genichimaruo.worldgenassist.mixin;

import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

import io.github.genichimaruo.worldgenassist.server.SeededLeafTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlendedNoise.class)
abstract class BlendedNoiseSeedTraceMixin {
	@Inject(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", at = @At("HEAD"), cancellable = true)
	private void worldgenAssist$replay(
		DensityFunction.FunctionContext context,
		CallbackInfoReturnable<Double> callback
	) {
		if (!SeededLeafTrace.hasActiveSession()) {
			return;
		}
		Double value = SeededLeafTrace.replayBlendedNoise((BlendedNoise)(Object)this, context);
		if (value != null) {
			callback.setReturnValue(value);
		}
	}

	@Inject(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", at = @At("RETURN"))
	private void worldgenAssist$record(
		DensityFunction.FunctionContext context,
		CallbackInfoReturnable<Double> callback
	) {
		if (SeededLeafTrace.hasActiveSession()) {
			SeededLeafTrace.recordBlendedNoise((BlendedNoise)(Object)this, context, callback.getReturnValue());
		}
	}
}
