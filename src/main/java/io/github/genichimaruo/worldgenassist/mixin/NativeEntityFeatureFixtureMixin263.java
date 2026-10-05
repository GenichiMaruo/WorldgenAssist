package io.github.genichimaruo.worldgenassist.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.genichimaruo.worldgenassist.server.FeatureFixture263;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
abstract class NativeEntityFeatureFixtureMixin263 {
	// Forge/Neo add this side-effect-only call; it is absent in generated Fabric26.3/primary26.2.
	@WrapOperation(method = "setPosRaw(DDD)V", require = 0,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
	private LevelChunk worldgenAssist$deferFixturePositionLoad(Level level, int x, int z, Operation<LevelChunk> original) {
		// The original return is discarded. Only the two explicitly paused fixture players skip
		// this synchronous FULL demand; ordinary movement and every other entity keep the call.
		if ((Object)this instanceof ServerPlayer player && FeatureFixture263.suspendPlayer(player)) return null;
		return original.call(level,x,z);
	}
}
