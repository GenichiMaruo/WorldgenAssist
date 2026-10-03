package io.github.genichimaruo.worldgenassist.server;

import com.mojang.serialization.MapCodec;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import io.github.genichimaruo.worldgenassist.common.GridDensityData;

/** One template per state/settings; never put a per-job graph in Minecraft's compiler cache. */
final class RemoteGridSamplers {
	private static final Map<RandomState, Map<NoiseGeneratorSettings, DensityFunction>> TEMPLATES = Collections.synchronizedMap(new WeakHashMap<>());
	private RemoteGridSamplers() { }
	static DensityFunction template(RandomState state, NoiseGeneratorSettings settings) {
		synchronized (TEMPLATES) {
			return TEMPLATES.computeIfAbsent(state, ignored -> new java.util.IdentityHashMap<>()).computeIfAbsent(settings, ignored -> {
				var inputs = GridDensityData.inputs(settings);
				DensitySampler[] fallback = new DensitySampler[GridDensityData.INPUT_COUNT];
				for (int i=0;i<fallback.length;i++) fallback[i] = state.getSampler(inputs.get(i));
				return new DfRewriteRule() {
					int next;
					@Override public DensityFunction rewrite(DensityFunction function) {
						function = DfRewriteRule.INLINE_REFERENCE.rewrite(function);
						if (function instanceof InterpolatedFunction node) {
							int index = next++;
							if (index >= fallback.length || !node.input().equals(inputs.get(index))) throw new IllegalArgumentException("Terrain grid template mismatch");
							return new InterpolatedFunction(new Input(index, node.input().range(), node.input().domainAxes(), fallback[index]), node.cellSizeXz(), node.cellSizeY());
						}
						return function.rewriteChildren(this);
					}
				}.rewrite(settings.noiseRouter().finalDensity());
			});
		}
	}
	private record Input(int index, Interval range, int domainAxes, DensitySampler fallback) implements DensityFunction {
		@Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
		@Override public MapCodec<Input> codec() { throw new UnsupportedOperationException("Transient terrain grid template"); }
		@Override public DensitySampler compileSampler(CompileContext context) {
			return new DensitySampler() {
				@Override public float sampleValue(SamplerContext context, int x, int y, int z) {
					var field = RemoteDensitySamplingScope.current();
					return field == null ? fallback.sampleValue(context,x,y,z) : field.gridValue(index,context,x,y,z,fallback);
				}
				@Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
					var field = RemoteDensitySamplingScope.current();
					if (field == null || !field.copyGrid(index, output, volume)) fallback.sampleVolume(context,output,volume);
				}
			};
		}
	}
}
