package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;

class PrivateBiomeCache263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	static final net.minecraft.core.HolderLookup.Provider LOOKUP = VanillaRegistries.createWorldLookup();
	static final Holder<Biome> PLAINS = LOOKUP.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
	static final Holder<Biome> DESERT = LOOKUP.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT);
	static final Holder<Biome> FOREST = LOOKUP.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.FOREST);
	static final PalettedContainerFactory CONTAINERS = containers();
	private static PalettedContainerFactory containers() {
		IdMapper<Holder<Biome>> ids = new IdMapper<>();
		LOOKUP.lookupOrThrow(Registries.BIOME).listElements().forEach(ids::add);
		var block = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
		var biome = Strategy.createForBiomes(ids);
		return new PalettedContainerFactory(block, Blocks.AIR.defaultBlockState(),
			PalettedContainer.codecRW(BlockState.CODEC, block, Blocks.AIR.defaultBlockState()), biome,
			PLAINS, PalettedContainer.codecRO(Biome.CODEC, biome, PLAINS));
	}
	static ProtoChunk chunk(ChunkPos pos) {
		ProtoChunk result = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(0, 16), CONTAINERS, null);
		result.fillBiomesFromNoise((x, y, z) -> PLAINS);
		result.setPersistedStatus(ChunkStatus.BIOMES);
		return result;
	}
	@Test void terrainAndBiomeMutationsNeverPolluteCachedCanonicalInput() {
		var cache = new PrivateBiomeChunkCache(2);
		var pos = new ChunkPos(-2, 3);
		var source = cache.canonical(pos, () -> chunk(pos));
		var copy = PrivateBiomeChunkCache.copyCenter(source, CONTAINERS);
		copy.getSection(0).setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
		copy.fillBiomesFromNoise((x, y, z) -> DESERT);
		assertSame(source, cache.canonical(pos, () -> { throw new AssertionError("Cache did not reuse"); }));
		assertEquals(Blocks.AIR.defaultBlockState(), source.getSection(0).getBlockState(0, 0, 0));
		assertSame(PLAINS, source.getNoiseBiome(-8, 0, 12));
		assertSame(DESERT, copy.getNoiseBiome(-8, 0, 12));
		assertTrue(PrivateBiomeChunkCache.copyCenter(source, CONTAINERS).getSection(0).hasOnlyAir());
	}
	@Test void boundedEvictionAndContextClearRecomputeAndRefuseTerrainSources() {
		var cache = new PrivateBiomeChunkCache(2);
		var created = new AtomicInteger();
		java.util.function.Function<ChunkPos, ProtoChunk> get = pos -> cache.canonical(pos, () -> { created.incrementAndGet(); return chunk(pos); });
		var a = new ChunkPos(0, 0); var b = new ChunkPos(1, 0); var c = new ChunkPos(2, 0);
		get.apply(a); get.apply(b); get.apply(a); get.apply(c); get.apply(b);
		assertEquals(4, created.get());
		cache.clear(); get.apply(a); assertEquals(5, created.get());
		var terrain = chunk(new ChunkPos(3, 0)); terrain.getSection(0).setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
		assertThrows(IllegalArgumentException.class, () -> cache.canonical(terrain.getPos(), () -> terrain));
		var wrong = chunk(new ChunkPos(4, 0)); wrong.setPersistedStatus(ChunkStatus.TERRAIN);
		assertThrows(IllegalArgumentException.class, () -> cache.canonical(wrong.getPos(), () -> wrong));
		assertThrows(IllegalArgumentException.class, () -> new PrivateBiomeChunkCache(513));
	}
}
