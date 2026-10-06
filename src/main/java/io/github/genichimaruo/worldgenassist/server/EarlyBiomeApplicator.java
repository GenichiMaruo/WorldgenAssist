package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayList;
import java.util.Set;
import io.github.genichimaruo.worldgenassist.common.CompleteBiomeData;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Preflight private copies with the ORIGINAL section filler, then fill the live
 * original chunk. No climate math, imported containers, status publication or partial retry. */
final class EarlyBiomeApplicator {
	private EarlyBiomeApplicator() { }
	static Prepared prepare(CompleteBiomeData data, ChunkAccess chunk, NoiseBasedChunkGenerator generator,
		HolderLookup.Provider registries, Runnable authority) {
		if (chunk.getClass() != ProtoChunk.class || chunk.getMinY() != data.minY() || chunk.getHeight() != data.height()
			|| chunk.getSections().length != data.height() / 16 || chunk.getHighestGeneratedStatus().isOrAfter(ChunkStatus.BIOMES)
			|| chunk.isUpgrading() || chunk.isOldNoiseGeneration() || chunk.getBelowZeroRetrogen() != null
			|| generator.getClass() != NoiseBasedChunkGenerator.class
			|| !(generator.getBiomeSource() instanceof MultiNoiseBiomeSource source)
			|| !source.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
			|| generator.generatorSettings().value().noiseSettings().minY() != data.minY()
			|| generator.generatorSettings().value().noiseSettings().height() != data.height()) {
			throw new IllegalArgumentException("Unsupported early biome target");
		}
		ProtoChunk proto=(ProtoChunk)chunk;
		if (!proto.getBlockEntities().isEmpty() || !proto.getBlockEntityNbts().isEmpty()) throw new IllegalArgumentException("Biome target has block entities");
		for (var offsets : chunk.getPostProcessing()) if (offsets != null && !offsets.isEmpty()) throw new IllegalArgumentException("Biome target has postprocessing");
		var registry = registries.lookupOrThrow(Registries.BIOME);
		Holder<Biome> plains = registry.getOrThrow(Biomes.PLAINS);
		ArrayList<Holder<Biome>> choices = new ArrayList<>(data.names().size());
		for (String name : data.names()) choices.add(registry.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(name))));
		Set<Holder<Biome>> possible = generator.getBiomeSource().possibleBiomes();
		LevelChunkSection[] target = chunk.getSections(); LevelChunkSection[] copies = new LevelChunkSection[target.length];
		int minQuartX = Math.multiplyExact(chunk.getPos().x(), 4), minQuartZ = Math.multiplyExact(chunk.getPos().z(), 4);
		BiomeResolver resolver = (x,y,z) -> {
			int dx = x - minQuartX, dz = z - minQuartZ, dy = y - data.minY() / 4;
			if (dx < 0 || dx > 3 || dz < 0 || dz > 3 || dy < 0 || dy >= data.height() / 4) {
				throw new IllegalArgumentException("Biome resolver outside assigned center");
			}
			return choices.get(data.code(dy >> 2, dx, dy & 3, dz));
		};
		for (int s = 0; s < target.length; s++) {
			if (target[s].getClass() != LevelChunkSection.class || target[s].getBiomes().getClass() != net.minecraft.world.level.chunk.PalettedContainer.class
				|| !target[s].hasOnlyAir()) throw new IllegalArgumentException("Changed biome target section");
			target[s].getBiomes().forEachInPalette(b -> { if (b != plains) throw new IllegalArgumentException("Biome target already filled"); });
			for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
				if (!possible.contains(choices.get(data.code(s,x,y,z)))) throw new IllegalArgumentException("Biome voxel outside original source domain");
			}
			copies[s] = target[s].copy();
			copies[s].fillBiomesFromNoise(resolver, minQuartX, data.minY() / 4 + s * 4, minQuartZ);
		}
		// Original recreate/resize/global palette behavior must reproduce ALL palette entries as well as voxels.
		if (!data.equals(CompleteBiomeData.capture(data.minY(), data.height(), copies))) throw new IllegalArgumentException("Original biome palette preflight differs");
		authority.run();
		return new Prepared(chunk, target.clone(), resolver, authority);
	}
	static final class Prepared {
		private final ChunkAccess chunk;
		private final LevelChunkSection[] sections;
		private final BiomeResolver resolver;
		private final Object[] biomeContainers;
		private final Runnable authority;
		private boolean applied;
		Prepared(ChunkAccess chunk, LevelChunkSection[] sections, BiomeResolver resolver, Runnable authority) {
			this.chunk=chunk; this.sections=sections; this.resolver=resolver; this.authority=authority;
			this.biomeContainers=java.util.Arrays.stream(sections).map(LevelChunkSection::getBiomes).toArray();
		}
		boolean apply() {
			if (applied) throw new IllegalStateException("Repeated early biome application");
			for (int i=0;i<sections.length;i++) if (chunk.getSection(i)!=sections[i] || sections[i].getBiomes()!=biomeContainers[i]) return false;
			try { authority.run(); }
			catch (java.util.concurrent.CancellationException | IllegalArgumentException unavailable) { return false; }
			applied=true;
			chunk.fillBiomesFromNoise(resolver);
			return true;
		}
	}
}
