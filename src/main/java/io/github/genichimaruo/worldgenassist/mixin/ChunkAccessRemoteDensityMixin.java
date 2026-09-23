package io.github.genichimaruo.worldgenassist.mixin;

import java.util.UUID;

import net.minecraft.world.level.chunk.ChunkAccess;

import io.github.genichimaruo.worldgenassist.server.RemoteDensityField;
import io.github.genichimaruo.worldgenassist.server.RemoteDensityTarget;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkAccess.class)
abstract class ChunkAccessRemoteDensityMixin implements RemoteDensityTarget {
	@Unique private volatile @Nullable RemoteDensityField worldgenAssist$remoteDensity;

	@Override
	public void worldgenAssist$installRemoteDensity(RemoteDensityField field) {
		ChunkAccess chunk = (ChunkAccess)(Object)this;
		if (field.chunkX() != chunk.getPos().x() || field.chunkZ() != chunk.getPos().z()) {
			throw new IllegalArgumentException("Remote density is for another chunk");
		}
		worldgenAssist$remoteDensity = field;
	}

	@Override
	public void worldgenAssist$clearRemoteDensity(UUID jobId) {
		RemoteDensityField current = worldgenAssist$remoteDensity;
		if (current != null && current.jobId().equals(jobId)) {
			worldgenAssist$remoteDensity = null;
		}
	}

	@Override
	public @Nullable RemoteDensityField worldgenAssist$getRemoteDensity() {
		return worldgenAssist$remoteDensity;
	}
}
