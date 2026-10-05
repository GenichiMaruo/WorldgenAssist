package io.github.genichimaruo.worldgenassist.mixin;

import java.util.concurrent.Executor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.genichimaruo.worldgenassist.server.FeatureStageConfig;
import io.github.genichimaruo.worldgenassist.server.FeatureStageDispatcher;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkMap.class)
abstract class ChunkMapFeatureSchedulerMixin263 {
	@WrapOperation(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/server/level/ChunkTaskDispatcher"))
	private ChunkTaskDispatcher worldgenAssist$featureBackpressure(TaskScheduler<Runnable> scheduler, Executor executor,
		Operation<ChunkTaskDispatcher> original, @Local(argsOnly = true) ServerLevel level,
		@Local(argsOnly = true) ChunkGenerator generator) {
		FeatureStageConfig config = FeatureStageConfig.current();
		if (!scheduler.name().equals("worldgen")
			|| generator.getClass() != NoiseBasedChunkGenerator.class || !level.dimension().equals(Level.OVERWORLD))
			return original.call(scheduler, executor);
		if (config == FeatureStageConfig.OFF) {
			io.github.genichimaruo.worldgenassist.server.FeatureFixture263.register(level, scheduler);
			return original.call(scheduler, executor);
		}
		// The constructor-created consecutive queue has no messages yet. Replace it, do not add a second queue.
		scheduler.close();
		var replacement = new FeatureStageDispatcher(level, executor, config);
		io.github.genichimaruo.worldgenassist.server.FeatureFixture263.register(level, replacement);
		return original.call(replacement, executor);
	}
}
