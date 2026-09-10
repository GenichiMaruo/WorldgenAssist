package io.github.genichimaruo.worldgenassist.mixin;

import java.util.UUID;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

import io.github.genichimaruo.worldgenassist.server.RemoteDensityField;
import io.github.genichimaruo.worldgenassist.server.RemoteDensityTarget;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.class)
abstract class NoiseChunkRemoteDensityMixin implements RemoteDensityTarget {
	@Shadow @Final private int cellWidth;
	@Shadow @Final private int cellHeight;
	@Shadow @Final private int cellCountXZ;
	@Shadow @Final private int cellCountY;
	@Shadow @Final private int cellNoiseMinY;
	@Shadow @Final private int firstCellX;
	@Shadow @Final private int firstCellZ;
	@Shadow @Final private DensityFunction fullNoiseDensity;

	@Unique private volatile RemoteDensityField worldgenAssist$remoteDensity;
	@Unique private int worldgenAssist$currentCellX;

	@Override
	public void worldgenAssist$installRemoteDensity(RemoteDensityField densityField) {
		if (densityField.cellWidth() != cellWidth
			|| densityField.cellHeight() != cellHeight
			|| densityField.height() / cellHeight != cellCountY
			|| 16 / cellWidth != cellCountXZ
			|| Math.floorDiv(densityField.minY(), cellHeight) != cellNoiseMinY
			|| Math.floorDiv(Math.multiplyExact(densityField.chunkX(), 16), cellWidth) != firstCellX
			|| Math.floorDiv(Math.multiplyExact(densityField.chunkZ(), 16), cellWidth) != firstCellZ) {
			throw new IllegalArgumentException("Remote density geometry does not match NoiseChunk");
		}
		worldgenAssist$remoteDensity = densityField;
	}

	@Override
	public void worldgenAssist$clearRemoteDensity(UUID jobId) {
		RemoteDensityField current = worldgenAssist$remoteDensity;
		if (current != null && current.jobId().equals(jobId)) {
			worldgenAssist$remoteDensity = null;
		}
	}

	@Inject(method = "advanceCellX(I)V", at = @At("HEAD"))
	private void worldgenAssist$captureCellX(int cellXIndex, CallbackInfo callback) {
		worldgenAssist$currentCellX = cellXIndex;
	}

	@Redirect(
		method = "selectCellYZ(II)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/DensityFunction;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"
		)
	)
	private void worldgenAssist$fillFinalDensityFromRemote(
		DensityFunction densityFunction,
		double[] output,
		DensityFunction.ContextProvider contextProvider,
		int cellYIndex,
		int cellZIndex
	) {
		RemoteDensityField remoteDensity = worldgenAssist$remoteDensity;
		DensityFunction finalNoiseFiller = ((NoiseChunkCacheAllInCellAccessor)fullNoiseDensity).worldgenAssist$getNoiseFiller();
		if (remoteDensity != null && densityFunction == finalNoiseFiller) {
			remoteDensity.copyCell(worldgenAssist$currentCellX, cellYIndex, cellZIndex, output);
		} else {
			densityFunction.fillArray(output, contextProvider);
		}
	}
}
