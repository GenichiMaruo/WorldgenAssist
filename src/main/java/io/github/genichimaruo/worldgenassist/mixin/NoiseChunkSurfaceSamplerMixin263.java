package io.github.genichimaruo.worldgenassist.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.genichimaruo.worldgenassist.server.RemoteDensitySamplingScope;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NoiseChunk.class)
abstract class NoiseChunkSurfaceSamplerMixin263 {
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/world/level/levelgen/RandomState;samplersWithContext(Lnet/minecraft/world/level/levelgen/densityfunction/SamplerContext;)Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;"))
	private DensitySamplerSet worldgenAssist$surfaceSamplers(RandomState receiver, SamplerContext context,
		Operation<DensitySamplerSet> original, RandomState state, Beardifier beardifier,
		NoiseGeneratorSettings settings, Aquifer.FluidPicker picker, Blender blender, DensityVolume volume) {
		DensitySamplerSet samplers = original.call(receiver, context);
		var field = RemoteDensitySamplingScope.current();
		return field != null && (beardifier == null || beardifier == Beardifier.EMPTY) && blender.isEmpty()
			? field.wrapSurfaceSamplers(state, settings, volume, samplers) : samplers;
	}
}
