package io.github.genichimaruo.worldgenassist.mixin;

import io.github.genichimaruo.worldgenassist.common.PrivateBiomeAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkGenerator.class)
public interface PrivateBiomeInvoker263 extends PrivateBiomeAccess {
	@Override @Invoker("doCreateBiomes")
	void worldgenAssist$invokeCreateBiomes(Blender blender, RandomState state, ChunkAccess chunk);
}
