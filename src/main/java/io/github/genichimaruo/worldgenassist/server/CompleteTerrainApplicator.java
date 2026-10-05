package io.github.genichimaruo.worldgenassist.server;

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
	public static Prepared prepare(TerrainDensityJob job, io.github.genichimaruo.worldgenassist.common.TerrainDensityResult result, ChunkAccess chunk,
		NoiseBasedChunkGenerator generator, RandomState state, StructureManager structures, Blender blender,
		WorldGenRegion region, Set<Holder<Biome>> possibleBiomes, Runnable requireAuthority) {
		Objects.requireNonNull(job); Objects.requireNonNull(result); Objects.requireNonNull(chunk);
		if (!job.identity().equals(result.identity())) throw new IllegalArgumentException("Complete result assignment differs");
		CompleteTerrainData data=result.completeTerrain();
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
			|| !job.shaping().equals(io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData.capture(
				Beardifier.forStructuresInChunk(structures, chunk.getPos())))
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
		var biomeInputs = TerrainBiomeWindow.inspect(chunk.getPos().x(), chunk.getPos().z(),
			job.minY(), job.height(), (x, z) -> region.getChunk(x, z));
		byte[] expectedBiomes = biomeInputs.digest();
		if (!biomeInputs.possibleBiomes().equals(possibleBiomes)) throw new IllegalArgumentException("Surface biome optimization inputs differ");
		data=result.selectCompleteTerrainForBiomes(expectedBiomes);
		CompleteTerrainPalette palette = new CompleteTerrainPalette();
		BlockState[] states = new BlockState[palette.size()];
		boolean[] surfaceStates = new boolean[states.length], floorStates = new boolean[states.length];
		for (int i = 0; i < states.length; i++) {
			states[i] = palette.state(i);
			surfaceStates[i] = !states[i].isAir();
			floorStates[i] = states[i].is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP);
		}
		short[] surface = data.surfaceHeights(), floor = data.floorHeights();
		short[] highestWrites = prepareColumns(data, surfaceStates, floorStates);
		ShortArrayList[] offsets = new ShortArrayList[job.height() / 16];
		for (int i = 0; i < offsets.length; i++) offsets[i] = new ShortArrayList(data.postProcessing(i));
		var prepared = new Prepared(chunk, data, states, highestWrites, packHeights(surface, job.height()), packHeights(floor, job.height()), offsets,
			result.hasPeerBiomeAlternative() ? () -> io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info(
				"[CAWG] job.peer_biome_choice_applied id={} side={}",job.identity().jobId(),
				java.security.MessageDigest.isEqual(expectedBiomes,result.completeTerrain().biomeWindowDigest()) ? "primary" : "peer") : () -> {});
		requireAuthority.run();
		return prepared;
	}

	/** Both WG predicates use their first match; lower voxels cannot change that height. */
	static short[] prepareColumns(CompleteTerrainData data, boolean[] surfaceStates, boolean[] floorStates) {
		short[] surface = data.surfaceHeights(), floor = data.floorHeights();
		short[] highestWrites = new short[256];
		int height = data.height();
		for (int column = 0; column < 256; column++) {
			int expectedSurface = 0, expectedFloor = 0, highest = -1;
			for (int y = height - 1; y >= 0; y--) {
				int code = data.choice(column * height + y);
				// CAVE_AIR is still a real write even above both WG heightmaps.
				if (highest < 0 && code != 0) highest = y;
				if (expectedSurface == 0 && surfaceStates[code]) expectedSurface = y + 1;
				if (expectedFloor == 0 && floorStates[code]) expectedFloor = y + 1;
				if (expectedSurface != 0 && expectedFloor != 0) break;
			}
			if (surface[column] != expectedSurface || floor[column] != expectedFloor) {
				throw new IllegalArgumentException("Complete terrain heightmap differs from final choices");
			}
			highestWrites[column] = (short)highest;
		}
		return highestWrites;
	}

	/** Caller owns all section locks. Preserves z/x/descending-y write order and counts. */
	static void writeBlocks(CompleteTerrainData data, BlockState[] states, short[] highestWrites,
		net.minecraft.world.level.chunk.LevelChunkSection[] sections) {
		int height = data.height();
		for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
			int column = z * 16 + x, base = column * height;
			for (int y = highestWrites[column]; y >= 0; y--) {
				int code = data.choice(base + y);
				if (code != 0) sections[y >> 4].setBlockState(x, y & 15, z, states[code], false);
			}
		}
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
		private final short[] highestWrites;
		private final long[] surface, floor;
		private final ShortArrayList[] offsets;
		private final Runnable selected;
		private boolean used;
		private Prepared(ChunkAccess chunk, CompleteTerrainData data, BlockState[] states, short[] highestWrites,
			long[] surface, long[] floor, ShortArrayList[] offsets, Runnable selected) {
			this.chunk = chunk; this.data = data; this.states = states;
			this.highestWrites = highestWrites;
			this.surface = surface; this.floor = floor; this.offsets = offsets;
			this.selected=selected;
		}
		/** Generation thread only, exactly once. Never invoke vanilla after a failure here. */
		public ChunkAccess apply() {
			if (used) throw new IllegalStateException("Complete terrain already applied");
			used = true;
			int acquired = 0;
			try {
				for (var section : chunk.getSections()) { section.acquire(); acquired++; }
				writeBlocks(data, states, highestWrites, chunk.getSections());
			} finally {
				for (int i = acquired - 1; i >= 0; i--) chunk.getSection(i).release();
			}
			chunk.setHeightmap(Heightmap.Types.WORLD_SURFACE_WG, surface);
			chunk.setHeightmap(Heightmap.Types.OCEAN_FLOOR_WG, floor);
			for (int i = 0; i < offsets.length; i++) if (!offsets[i].isEmpty()) chunk.addPackedPostProcess(offsets[i], i);
			chunk.markUnsaved();
			selected.run();
			return chunk;
		}
	}
}
