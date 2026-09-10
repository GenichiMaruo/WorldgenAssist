package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeedDependencyAnalyzerTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void inventoriesWiredVanillaOverworldFinalDensityWithoutExtractingSeedState() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		RandomState randomState = RandomState.create(settings.value(), noises, 8675309L);

		SeedDependencyAnalyzer.Report report = SeedDependencyAnalyzer.analyze(randomState.router().finalDensity());

		assertTrue(report.hasSeedDependentLeaves(), report.toString());
		assertTrue(report.fullyKeyedAndWired(), report.toString());
		assertEquals(
			List.of(
				"minecraft:cave_cheese",
				"minecraft:cave_entrance",
				"minecraft:cave_layer",
				"minecraft:continentalness",
				"minecraft:erosion",
				"minecraft:jagged",
				"minecraft:noodle",
				"minecraft:noodle_ridge_a",
				"minecraft:noodle_ridge_b",
				"minecraft:noodle_thickness",
				"minecraft:offset",
				"minecraft:pillar",
				"minecraft:pillar_rareness",
				"minecraft:pillar_thickness",
				"minecraft:ridge",
				"minecraft:spaghetti_2d",
				"minecraft:spaghetti_2d_elevation",
				"minecraft:spaghetti_2d_modulator",
				"minecraft:spaghetti_2d_thickness",
				"minecraft:spaghetti_3d_1",
				"minecraft:spaghetti_3d_2",
				"minecraft:spaghetti_3d_rarity",
				"minecraft:spaghetti_3d_thickness",
				"minecraft:spaghetti_roughness",
				"minecraft:spaghetti_roughness_modulator"
			),
			report.keyedNoises().stream().map(Identifier::toString).toList(),
			report.toString()
		);
		assertEquals(1_116, report.noiseHolderReferences(), report.toString());
		assertEquals(1, report.blendedNoiseNodes(), report.toString());
		assertFalse(report.functionTypes().isEmpty(), report.toString());
	}

	@Test
	void identifiesUnwiredVanillaGraphAsUnsafeForReplay() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);

		SeedDependencyAnalyzer.Report report = SeedDependencyAnalyzer.analyze(settings.value().noiseRouter().finalDensity());

		assertTrue(report.hasSeedDependentLeaves(), report.toString());
		assertTrue(report.unwiredNoiseHolderReferences() > 0, report.toString());
		assertFalse(report.fullyKeyedAndWired(), report.toString());
	}
}
