package io.github.genichimaruo.worldgenassist.mixin;

import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.genichimaruo.worldgenassist.server.SeededLeafTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NormalNoise.class)
abstract class NormalNoiseSeedTraceMixin {
	@Inject(method = "getValue(DDD)D", at = @At("HEAD"), cancellable = true)
	private void worldgenAssist$replay(
		double x,
		double y,
		double z,
		CallbackInfoReturnable<Double> callback
	) {
		if (!SeededLeafTrace.hasActiveSession()) {
			return;
		}
		Double value = SeededLeafTrace.replayNormalNoise((NormalNoise)(Object)this, x, y, z);
		if (value != null) {
			callback.setReturnValue(value);
		}
	}

	@Inject(method = "getValue(DDD)D", at = @At("RETURN"))
	private void worldgenAssist$record(
		double x,
		double y,
		double z,
		CallbackInfoReturnable<Double> callback
	) {
		if (SeededLeafTrace.hasActiveSession()) {
			SeededLeafTrace.recordNormalNoise((NormalNoise)(Object)this, x, y, z, callback.getReturnValue());
		}
	}
}
