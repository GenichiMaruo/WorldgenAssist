package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RemoteWorldgenEligibilityTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void acceptsEachVanillaNoiseSettingsSamplingRange() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		assertVanillaRange(registries, NoiseGeneratorSettings.OVERWORLD, -64, 384, 4, 8);
		assertVanillaRange(registries, NoiseGeneratorSettings.NETHER, 0, 128, 4, 8);
		assertVanillaRange(registries, NoiseGeneratorSettings.END, 0, 128, 8, 4);
	}

	@Test
	void acceptsNoiseRangeContainedWithinTheLevelInsteadOfRequiringEqualLevelHeight() {
		NoiseSettings netherRange = vanillaNoise(NoiseGeneratorSettings.NETHER);
		assertTrue(RemoteWorldgenEligibility.hasProtocolGeometry(netherRange, -64, 256));
	}

	@Test
	void rejectsProtocolGeometryOutsideItsContainingLevel() {
		NoiseSettings netherRange = vanillaNoise(NoiseGeneratorSettings.NETHER);
		assertFalse(RemoteWorldgenEligibility.hasProtocolGeometry(netherRange, 1, 128));
		assertFalse(RemoteWorldgenEligibility.hasProtocolGeometry(netherRange, 0, 127));
	}

	private static void assertVanillaRange(
		HolderLookup.Provider registries,
		net.minecraft.resources.ResourceKey<NoiseGeneratorSettings> settingsKey,
		int expectedMinY,
		int expectedHeight,
		int expectedCellWidth,
		int expectedCellHeight
	) {
		NoiseSettings noise = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey).value().noiseSettings();
		assertEquals(expectedMinY, noise.minY(), "vanilla minY for " + settingsKey.identifier());
		assertEquals(expectedHeight, noise.height(), "vanilla height for " + settingsKey.identifier());
		assertEquals(expectedCellWidth, noise.getCellWidth(), "vanilla cell width for " + settingsKey.identifier());
		assertEquals(expectedCellHeight, noise.getCellHeight(), "vanilla cell height for " + settingsKey.identifier());
		assertTrue(RemoteWorldgenEligibility.hasProtocolGeometry(noise, expectedMinY, expectedHeight));
	}

	private static NoiseSettings vanillaNoise(net.minecraft.resources.ResourceKey<NoiseGeneratorSettings> settingsKey) {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		return registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey).value().noiseSettings();
	}
}
