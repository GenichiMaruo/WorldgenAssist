package io.github.genichimaruo.worldgenassist.server;

import io.github.genichimaruo.worldgenassist.common.TerrainDecisionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** Server-owned 26.3 doFill loop. Call only after requireDecisionFill, under vanilla's section locks. */
public final class TerrainDecisionFiller {
	private TerrainDecisionFiller() { }
	public static void fill(RemoteDensityField field, DensityVolume volume, ChunkAccess chunk) {
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		int height = volume.sizeY();
		for (int z = 0; z < 16; z++) {
			int blockZ = volume.blockZ(z);
			for (int x = 0; x < 16; x++) {
				int blockX = volume.blockX(x);
				int base = (z * 16 + x) * height;
				int oceanFloorSkip = oceanFloor.getFirstAvailable(x,z) - 2;
				int worldSurfaceSkip = worldSurface.getFirstAvailable(x,z) - 2;
				for (int y = field.highestDecision(z * 16 + x); y >= 0; y--) {
					int code = field.decisionCode(base + y);
					// Vanilla never writes or schedules postprocessing for its AIR outcome.
					if (code == 1 || code == 2) continue;
					int blockY = volume.blockY(y);
					LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(blockY));
					BlockState state = field.decisionState(code);
					// DEBUG_AQUIFERS is rejected before entry, so vanilla's debug helper is identity.
					if (state != air) {
						section.setBlockState(x, SectionPos.sectionRelative(blockY), z, state, false);
						// Exact Heightmap.update early-out, including preexisting heightmaps.
						if (blockY > oceanFloorSkip && oceanFloor.update(x, blockY, z, state)) {
							oceanFloorSkip = oceanFloor.getFirstAvailable(x,z) - 2;
						}
						if (blockY > worldSurfaceSkip && worldSurface.update(x, blockY, z, state)) {
							worldSurfaceSkip = worldSurface.getFirstAvailable(x,z) - 2;
						}
						if (TerrainDecisionData.schedulesFluid(code) && !state.getFluidState().isEmpty()) {
							blockPos.set(blockX, blockY, blockZ);
							chunk.markPosForPostProcessing(blockPos);
						}
					}
				}
			}
		}
		field.recordDecisionFill();
	}
}
