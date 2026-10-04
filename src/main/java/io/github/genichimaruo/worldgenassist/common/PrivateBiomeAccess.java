package io.github.genichimaruo.worldgenassist.common;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

public interface PrivateBiomeAccess {
	void worldgenAssist$invokeCreateBiomes(Blender blender, RandomState state, ChunkAccess chunk);
}
