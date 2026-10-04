package io.github.genichimaruo.worldgenassist.common;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** Private chunk only. No world, tickets, entity packets, renderer or asynchronous game executor. */
public final class PrivateTerrainComputer {
	private final PalettedContainerFactory containers;
	private final CompleteTerrainPalette palette;
	private final PrivateBiomeChunkCache biomeCache = new PrivateBiomeChunkCache(512);
	private NoiseBasedChunkGenerator cachedGenerator;
	private RandomState cachedState;
	private int cachedMinY, cachedHeight;
	private long completedComputations;

	public PrivateTerrainComputer(HolderLookup.Provider registries) {
		var biomes = registries.lookupOrThrow(Registries.BIOME);
		IdMapper<Holder<Biome>> biomeIds = new IdMapper<>();
		biomes.listElements().forEach(biomeIds::add);
		var blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
		var biomeStrategy = Strategy.createForBiomes(biomeIds);
		Holder<Biome> defaultBiome = biomes.getOrThrow(Biomes.PLAINS);
		containers = new PalettedContainerFactory(blockStrategy, Blocks.AIR.defaultBlockState(),
			PalettedContainer.codecRW(BlockState.CODEC, blockStrategy, Blocks.AIR.defaultBlockState()),
			biomeStrategy, defaultBiome, PalettedContainer.codecRO(Biome.CODEC, biomeStrategy, defaultBiome));
		palette = new CompleteTerrainPalette();
	}

	public CompleteTerrainData compute(TerrainDensityJob job, NoiseBasedChunkGenerator generator, RandomState state) {
		var settings = generator.generatorSettings().value();
		if (generator.getClass() != NoiseBasedChunkGenerator.class
			|| !(generator.getBiomeSource() instanceof MultiNoiseBiomeSource biomes)
			|| !biomes.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
			|| !TerrainDecisionData.supports(job.noiseSettings(), settings)
			|| settings.defaultBlock() != Blocks.STONE.defaultBlockState()
			|| job.minY() != settings.noiseSettings().minY() || job.height() != settings.noiseSettings().height()
			|| state.seed() != job.worldSeed() || SharedConstants.DEBUG_DISABLE_SURFACE
			|| SharedConstants.DEBUG_DISABLE_CARVERS || SharedConstants.DEBUG_ONLY_GENERATE_HALF_THE_WORLD
			|| SharedConstants.debugVoidTerrain(job.identity().chunkPos())) {
			throw new IllegalArgumentException("Unsupported private terrain context");
		}
		if (!((Object)generator instanceof PrivateBiomeAccess biomeAccess) || !((Object)generator instanceof PrivateTerrainAccess terrainAccess)) {
			throw new IllegalStateException("Private terrain invokers are unavailable");
		}
		LevelHeightAccessor height = LevelHeightAccessor.create(job.minY(), job.height());
		if (cachedGenerator != generator || cachedState != state || cachedMinY != job.minY() || cachedHeight != job.height()) {
			biomeCache.clear(); cachedGenerator = generator; cachedState = state;
			cachedMinY = job.minY(); cachedHeight = job.height();
		}
		ProtoChunk[][] window = new ProtoChunk[3][3];
		Set<Holder<Biome>> possibleBiomes = new HashSet<>();
		int centerX = job.identity().chunkX(), centerZ = job.identity().chunkZ();
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
			checkCancellation();
			ChunkPos pos = new ChunkPos(centerX + x - 1, centerZ + z - 1);
			ProtoChunk source = biomeCache.canonical(pos, () -> {
				ProtoChunk created = new ProtoChunk(pos, UpgradeData.EMPTY, height, containers, null);
				biomeAccess.worldgenAssist$invokeCreateBiomes(Blender.empty(), state, created);
				created.setPersistedStatus(ChunkStatus.BIOMES);
				return created;
			});
			ProtoChunk chunk = x == 1 && z == 1 ? PrivateBiomeChunkCache.copyCenter(source, containers) : source;
			chunk.collectBiomesInPalette(possibleBiomes);
			window[z][x] = chunk;
		}
		ProtoChunk center = window[1][1];
		BiomeResolver uncached = biomes.createUncachedResolver(state);
		BiomeResolver resolver = (qx, qy, qz) -> {
			int dx = Math.floorDiv(qx, 4) - centerX + 1, dz = Math.floorDiv(qz, 4) - centerZ + 1;
			return dx >= 0 && dx < 3 && dz >= 0 && dz < 3
				? window[dz][dx].getNoiseBiome(qx, qy, qz) : uncached.getNoiseBiome(qx, qy, qz);
		};
		BiomeManager manager = new BiomeManager(resolver, BiomeManager.obfuscateSeed(job.worldSeed()));
		DensityVolume volume = BlockDensityData.volume(job);
		try (NoiseChunk noise = new NoiseChunk(state, job.shaping().sampler(), settings,
			TerrainDecisionData.fluidPicker(settings), Blender.empty(), volume)) {
			int acquired = 0;
			try {
				for (var section : center.getSections()) { section.acquire(); acquired++; }
				checkCancellation();
				terrainAccess.worldgenAssist$invokeDoFill(noise, center);
			} finally {
				for (int i = acquired - 1; i >= 0; i--) center.getSection(i).release();
			}
			checkCancellation();
			var rule = settings.materialRule().value();
			terrainAccess.worldgenAssist$invokeBuildSurface(center, noise, state, manager, possibleBiomes, rule);
			checkCancellation();
			terrainAccess.worldgenAssist$invokeGenerateCarvers(center, Blender.empty(), noise, state, manager, null, rule);
		}
		checkCancellation();
		byte[] choices = new byte[256 * job.height()];
		short[] surface = new short[256], floor = new short[256];
		var surfaceMap = center.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		var floorMap = center.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
			checkCancellation();
			int column = z * 16 + x;
			surface[column] = (short)(surfaceMap.getFirstAvailable(x, z) - job.minY());
			floor[column] = (short)(floorMap.getFirstAvailable(x, z) - job.minY());
			for (int y = 0; y < job.height(); y++) {
				choices[column * job.height() + y] = palette.encode(center.getBlockState(
					pos.set(center.getPos().getBlockX(x), job.minY() + y, center.getPos().getBlockZ(z))));
			}
		}
		short[][] offsets = new short[job.height() / 16][];
		var original = center.getPostProcessing();
		for (int i = 0; i < offsets.length; i++) offsets[i] = original[i] == null ? new short[0] : original[i].toShortArray();
		byte[] digest = TerrainBiomeWindow.digest(centerX, centerZ, job.minY(), job.height(),
			(x, z) -> window[z - centerZ + 1][x - centerX + 1]);
		CompleteTerrainData result = new CompleteTerrainData(job.minY(), job.height(), choices, surface, floor, offsets, digest);
		if (++completedComputations % 128 == 0) io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info(
			"[CAWG] private_biome_cache computations={} hits={} misses={} entries={}",
			completedComputations, biomeCache.hits(), biomeCache.misses(), biomeCache.size());
		return result;
	}
	private static void checkCancellation() {
		if (Thread.currentThread().isInterrupted()) throw new CancellationException("Private terrain interrupted");
	}
}
