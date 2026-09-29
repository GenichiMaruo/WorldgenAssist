package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RemoteWorldgenEligibility263Test {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void customGeneratorMustExplicitlyDeclareItsVanillaNoiseDelegate() {
		var lookup = VanillaRegistries.createWorldLookup();
		BiomeSource biomes = new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
		Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		NoiseBasedChunkGenerator vanilla = new NoiseBasedChunkGenerator(biomes, settings);
		assertSame(vanilla, RemoteWorldgenEligibility.noiseDelegate(vanilla));
		FlatLevelGeneratorSettings flat = FlatLevelGeneratorSettings.getDefault(
			lookup.lookupOrThrow(Registries.BIOME),
			lookup.lookupOrThrow(Registries.STRUCTURE_SET),
			lookup.lookupOrThrow(Registries.PLACED_FEATURE)
		);
		assertNull(RemoteWorldgenEligibility.noiseDelegate(new FlatLevelSource(flat)));
		CompatibleGenerator compatible = new CompatibleGenerator(flat, vanilla);
		assertSame(vanilla, RemoteWorldgenEligibility.noiseDelegate(compatible));
	}

	private static final class CompatibleGenerator extends FlatLevelSource
		implements RemoteDensityCompatibleGenerator {
		private final NoiseBasedChunkGenerator delegate;

		CompatibleGenerator(FlatLevelGeneratorSettings flat, NoiseBasedChunkGenerator delegate) {
			super(flat);
			this.delegate = delegate;
		}

		@Override
		public NoiseBasedChunkGenerator worldgenAssist$noiseDelegate() {
			return delegate;
		}

		@Override
		public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender,
			RandomState randomState, StructureManager structures, BiomeManager biomes,
			WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
			return delegate.buildTerrain(chunk, blender, randomState, structures, biomes,
				carverBiomeRegion, possibleBiomes);
		}
	}
}
