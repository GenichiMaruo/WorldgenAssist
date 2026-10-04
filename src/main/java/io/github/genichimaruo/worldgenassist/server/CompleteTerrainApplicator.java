package io.github.genichimaruo.worldgenassist.server;

import java.security.MessageDigest;
import java.util.Objects;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import io.github.genichimaruo.worldgenassist.common.CompleteTerrainData;
import io.github.genichimaruo.worldgenassist.common.CompleteTerrainPalette;
import io.github.genichimaruo.worldgenassist.common.TerrainBiomeWindow;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDecisionData;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Set;

/** Called only after current owner/context/deadline validation by the manager. */
public final class CompleteTerrainApplicator {
	private CompleteTerrainApplicator() { }

	/** All rejectable checks and allocations finish before the first section write. */
	public static Prepared prepare(TerrainDensityJob job, CompleteTerrainData data, ChunkAccess chunk,
		NoiseBasedChunkGenerator generator, RandomState state, StructureManager structures, Blender blender,
		WorldGenRegion region, Set<Holder<Biome>> possibleBiomes) {
		Objects.requireNonNull(job); Objects.requireNonNull(data); Objects.requireNonNull(chunk);
		Objects.requireNonNull(generator); Objects.requireNonNull(state); Objects.requireNonNull(structures);
		Objects.requireNonNull(blender); Objects.requireNonNull(possibleBiomes);
		var settings = generator.generatorSettings().value();
		if (!(chunk instanceof ProtoChunk proto) || region == null
			|| chunk.getPersistedStatus() != ChunkStatus.BIOMES || chunk.isUpgrading() || chunk.isOldNoiseGeneration()
			|| !job.identity().chunkPos().equals(chunk.getPos()) || chunk.getMinY() != job.minY()
			|| chunk.getHeight() != job.height() || data.minY() != job.minY() || data.height() != job.height()
			|| data.blockCount() != job.height() * 256 || chunk.getSections().length != job.height() / 16
			|| generator.getClass() != NoiseBasedChunkGenerator.class
			|| !(generator.getBiomeSource() instanceof MultiNoiseBiomeSource biomes)
			|| !biomes.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
			|| !job.noiseSettings().equals(generator.generatorSettings().unwrapKey().orElseThrow().identifier())
			|| !TerrainDecisionData.supports(job.noiseSettings(), settings)
			|| settings.defaultBlock() != Blocks.STONE.defaultBlockState()
			|| !settings.noiseSettings().equals(settings.noiseSettings().clampToHeightAccessor(chunk))
			|| settings.noiseSettings().minY() != job.minY() || settings.noiseSettings().height() != job.height()
			|| state.seed() != job.worldSeed() || !blender.isEmpty()
			|| Beardifier.forStructuresInChunk(structures, chunk.getPos()) != Beardifier.EMPTY
			|| !proto.getBlockEntities().isEmpty() || !proto.getBlockEntityNbts().isEmpty()
			|| SharedConstants.DEBUG_DISABLE_SURFACE || SharedConstants.DEBUG_DISABLE_CARVERS
			|| SharedConstants.DEBUG_ONLY_GENERATE_HALF_THE_WORLD || SharedConstants.debugVoidTerrain(chunk.getPos())) {
			throw new IllegalArgumentException("Complete terrain no longer matches eligible context");
		}
		for (var section : chunk.getSections()) if (!section.hasOnlyAir()) {
			throw new IllegalArgumentException("Complete terrain target already contains blocks");
		}
		var oldOffsets = chunk.getPostProcessing();
		for (var offsets : oldOffsets) if (offsets != null && !offsets.isEmpty()) {
			throw new IllegalArgumentException("Complete terrain target already has postprocessing");
		}
		byte[] expectedBiomes = TerrainBiomeWindow.digest(chunk.getPos().x(), chunk.getPos().z(),
			job.minY(), job.height(), (x, z) -> region.getChunk(x, z));
		Set<Holder<Biome>> actualPossibleBiomes = new java.util.HashSet<>();
		for (int z = chunk.getPos().z() - 1; z <= chunk.getPos().z() + 1; z++) {
			for (int x = chunk.getPos().x() - 1; x <= chunk.getPos().x() + 1; x++) region.getChunk(x, z).collectBiomesInPalette(actualPossibleBiomes);
		}
		if (!actualPossibleBiomes.equals(possibleBiomes)) throw new IllegalArgumentException("Surface biome optimization inputs differ");
		if (!MessageDigest.isEqual(expectedBiomes, data.biomeWindowDigest())) {
			throw new IllegalArgumentException("Complete terrain biome window differs from authoritative chunks");
		}
		CompleteTerrainPalette palette = new CompleteTerrainPalette();
		BlockState[] states = new BlockState[palette.size()];
		boolean[] surfaceStates = new boolean[states.length], floorStates = new boolean[states.length];
		for (int i = 0; i < states.length; i++) {
			states[i] = palette.state(i);
			surfaceStates[i] = !states[i].isAir();
			floorStates[i] = states[i].is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP);
		}
		short[] surface = data.surfaceHeights(), floor = data.floorHeights();
		for (int column = 0; column < 256; column++) {
			int expectedSurface = 0, expectedFloor = 0;
			for (int y = job.height() - 1; y >= 0; y--) {
				int code = data.choice(column * job.height() + y);
				if (expectedSurface == 0 && surfaceStates[code]) expectedSurface = y + 1;
				if (expectedFloor == 0 && floorStates[code]) expectedFloor = y + 1;
			}
			if (surface[column] != expectedSurface || floor[column] != expectedFloor) {
				throw new IllegalArgumentException("Complete terrain heightmap differs from final choices");
			}
		}
		ShortArrayList[] offsets = new ShortArrayList[job.height() / 16];
		for (int i = 0; i < offsets.length; i++) offsets[i] = new ShortArrayList(data.postProcessing(i));
		return new Prepared(chunk, data, states, packHeights(surface, job.height()), packHeights(floor, job.height()), offsets);
	}

	private static long[] packHeights(short[] heights, int height) {
		SimpleBitStorage bits = new SimpleBitStorage(Mth.ceillog2(height + 1), 256);
		for (int i = 0; i < heights.length; i++) bits.set(i, heights[i]);
		return bits.getRaw().clone();
	}

	public static final class Prepared {
		private final ChunkAccess chunk;
		private final CompleteTerrainData data;
		private final BlockState[] states;
		private final long[] surface, floor;
		private final ShortArrayList[] offsets;
		private boolean used;
		private Prepared(ChunkAccess chunk, CompleteTerrainData data, BlockState[] states,
			long[] surface, long[] floor, ShortArrayList[] offsets) {
			this.chunk = chunk; this.data = data; this.states = states;
			this.surface = surface; this.floor = floor; this.offsets = offsets;
		}
		/** Generation thread only, exactly once. Never invoke vanilla after a failure here. */
		public ChunkAccess apply() {
			if (used) throw new IllegalStateException("Complete terrain already applied");
			used = true;
			int acquired = 0;
			try {
				for (var section : chunk.getSections()) { section.acquire(); acquired++; }
				for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
					int column = z * 16 + x;
					for (int y = data.height() - 1; y >= 0; y--) {
						BlockState block = states[data.choice(column * data.height() + y)];
						if (block != Blocks.AIR.defaultBlockState()) {
							chunk.getSection(y / 16).setBlockState(x, y & 15, z, block, false);
						}
					}
				}
			} finally {
				for (int i = acquired - 1; i >= 0; i--) chunk.getSection(i).release();
			}
			chunk.setHeightmap(Heightmap.Types.WORLD_SURFACE_WG, surface);
			chunk.setHeightmap(Heightmap.Types.OCEAN_FLOOR_WG, floor);
			for (int i = 0; i < offsets.length; i++) if (!offsets[i].isEmpty()) chunk.addPackedPostProcess(offsets[i], i);
			chunk.markUnsaved();
			return chunk;
		}
	}
}
