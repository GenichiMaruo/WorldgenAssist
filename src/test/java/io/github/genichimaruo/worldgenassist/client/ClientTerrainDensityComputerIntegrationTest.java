package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.server.WorldgenContextFingerprintFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Direct protocol-v2 client calculation compared to a separate vanilla NoiseChunk traversal. */
class ClientTerrainDensityComputerIntegrationTest {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
	private static final Identifier NETHER = Identifier.parse("minecraft:the_nether");
	private static final Identifier END = Identifier.parse("minecraft:the_end");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@Timeout(90)
	void computesFullVanillaDimensionFieldsBitExactlyAgainstIndependentAuthoritativeTraversal() {
		assertClientMatchesAuthoritativeTraversal(OVERWORLD, NoiseGeneratorSettings.OVERWORLD, 8675309L, 0, 0, 4, 8);
		assertClientMatchesAuthoritativeTraversal(OVERWORLD, NoiseGeneratorSettings.OVERWORLD, 123456789L, -11, 7, 4, 8);
		assertClientMatchesAuthoritativeTraversal(NETHER, NoiseGeneratorSettings.NETHER, 8675309L, -11, 7, 4, 8);
		assertClientMatchesAuthoritativeTraversal(NETHER, NoiseGeneratorSettings.NETHER, -987654321L, 23, -19, 4, 8);
		assertClientMatchesAuthoritativeTraversal(END, NoiseGeneratorSettings.END, 8675309L, 1_000, -1_000, 8, 4);
		assertClientMatchesAuthoritativeTraversal(END, NoiseGeneratorSettings.END, -987654321L, -1_001, 997, 8, 4);
	}

	@Test
	void rejectsUnsupportedDimensionsGeometryAndContextMismatches() {
		Fixture fixture = fixture(OVERWORLD, NoiseGeneratorSettings.OVERWORLD, 8675309L, 10, -20);

		assertRejected(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT,
			() -> ClientTerrainDensityComputer.compute(fixture.registries(), Level.NETHER.identifier(), fixture.job()));
		Fixture netherFixture = fixture(NETHER, NoiseGeneratorSettings.NETHER, 8675309L, 10, -20);
		assertRejected(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT,
			() -> ClientTerrainDensityComputer.compute(netherFixture.registries(), OVERWORLD, netherFixture.job()));
		Fixture endFixture = fixture(END, NoiseGeneratorSettings.END, 8675309L, 1_000, -1_000);
		assertRejected(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT,
			() -> ClientTerrainDensityComputer.compute(endFixture.registries(), NETHER, endFixture.job()));

		TerrainDensityJob incompatibleGeometry = new TerrainDensityJob(
			fixture.job().identity(), fixture.job().worldSeed(), fixture.job().generateStructures(), fixture.job().noiseSettings(),
			fixture.job().minY(), fixture.job().height(), fixture.job().cellWidth() * 2, fixture.job().cellHeight()
		);
		assertRejected(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT,
			() -> ClientTerrainDensityComputer.compute(fixture.registries(), OVERWORLD, incompatibleGeometry));

		TerrainJobIdentity wrongIdentity = new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			UUID.fromString("02ca3c40-a568-42bf-b2f0-9bcd72f59772"),
			OVERWORLD,
			fixture.job().identity().chunkX(),
			fixture.job().identity().chunkZ(),
			WorldgenContextFingerprint.fromHex("11".repeat(WorldgenContextFingerprint.BYTE_LENGTH))
		);
		TerrainDensityJob contextMismatch = new TerrainDensityJob(
			wrongIdentity, fixture.job().worldSeed(), fixture.job().generateStructures(), fixture.job().noiseSettings(),
			fixture.job().minY(), fixture.job().height(), fixture.job().cellWidth(), fixture.job().cellHeight()
		);
		assertRejected(TerrainJobFailurePayload.Reason.CONTEXT_MISMATCH,
			() -> ClientTerrainDensityComputer.compute(fixture.registries(), OVERWORLD, contextMismatch));

		TerrainJobIdentity unknownDimensionIdentity = new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			UUID.fromString("3bb03bb1-0254-4b36-9dc9-28278227d5d4"),
			Identifier.parse("worldgenassist:unknown_dimension"),
			fixture.job().identity().chunkX(),
			fixture.job().identity().chunkZ(),
			fixture.job().identity().contextFingerprint()
		);
		TerrainDensityJob unknownDimension = new TerrainDensityJob(
			unknownDimensionIdentity, fixture.job().worldSeed(), fixture.job().generateStructures(), fixture.job().noiseSettings(),
			fixture.job().minY(), fixture.job().height(), fixture.job().cellWidth(), fixture.job().cellHeight()
		);
		assertRejected(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT,
			() -> ClientTerrainDensityComputer.compute(fixture.registries(), unknownDimensionIdentity.dimension(), unknownDimension));
	}

	@Test
	void observesThreadInterruptionAtTheClientSamplingBoundary() {
		Fixture fixture = fixture(OVERWORLD, NoiseGeneratorSettings.OVERWORLD, 8675309L, 10, -20);
		Thread.currentThread().interrupt();
		try {
			assertThrows(CancellationException.class,
				() -> ClientTerrainDensityComputer.compute(fixture.registries(), OVERWORLD, fixture.job()));
		} finally {
			Thread.interrupted();
		}
	}

	private static void assertClientMatchesAuthoritativeTraversal(
		Identifier dimension,
		ResourceKey<NoiseGeneratorSettings> settingsKey,
		long seed,
		int chunkX,
		int chunkZ,
		int expectedCellWidth,
		int expectedCellHeight
	) {
		Fixture fixture = fixture(dimension, settingsKey, seed, chunkX, chunkZ);
		assertEquals(expectedCellWidth, fixture.noiseSettings().getCellWidth(), "vanilla cell width for " + dimension);
		assertEquals(expectedCellHeight, fixture.noiseSettings().getCellHeight(), "vanilla cell height for " + dimension);
		TerrainDensityResult client = ClientTerrainDensityComputer.compute(fixture.registries(), dimension, fixture.job());
		double[] authoritative = new AuthoritativeDensitySampler(
			RandomState.create(fixture.settings().value(), fixture.noises(), seed),
			fixture.settings().value(),
			fixture.noiseSettings(),
			Math.multiplyExact(chunkX, TerrainDensityJob.CHUNK_SIDE),
			Math.multiplyExact(chunkZ, TerrainDensityJob.CHUNK_SIDE)
		).sample();

		assertEquals(fixture.job().identity(), client.identity());
		assertEquals(fixture.job().sampleCount(), client.densityCount());
		assertEquals(authoritative.length, client.densityCount());
		assertTrue(client.clientComputeNanos() >= 0L);
		for (int index = 0; index < authoritative.length; index++) {
			assertEquals(
				Double.doubleToRawLongBits(authoritative[index]),
				Double.doubleToRawLongBits(client.densityAt(index)),
				"dimension/seed/chunk/index=" + dimension + "/" + seed + "/" + chunkX + "," + chunkZ + "/" + index
			);
		}
	}

	private static Fixture fixture(
		Identifier dimension,
		ResourceKey<NoiseGeneratorSettings> settingsKey,
		long seed,
		int chunkX,
		int chunkZ
	) {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(settingsKey);
		NoiseSettings noiseSettings = settings.value().noiseSettings();
		WorldgenContextFingerprint fingerprint = WorldgenContextFingerprintFactory.create(
			registries, dimension, seed, true, noiseSettings.minY(), noiseSettings.height(), settings
		);
		TerrainJobIdentity identity = new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			UUID.nameUUIDFromBytes(("direct-v2/" + seed + "/" + chunkX + "/" + chunkZ).getBytes(StandardCharsets.UTF_8)),
			dimension,
			chunkX,
			chunkZ,
			fingerprint
		);
		TerrainDensityJob job = new TerrainDensityJob(
			identity,
			seed,
			true,
			settingsKey.identifier(),
			noiseSettings.minY(),
			noiseSettings.height(),
			noiseSettings.getCellWidth(),
			noiseSettings.getCellHeight()
		);
		return new Fixture(
			registries,
			settings,
			registries.lookupOrThrow(Registries.NOISE),
			noiseSettings,
			job
		);
	}

	private static void assertRejected(
		TerrainJobFailurePayload.Reason expected,
		org.junit.jupiter.api.function.Executable action
	) {
		ClientTerrainDensityComputer.RejectedJobException exception = assertThrows(
			ClientTerrainDensityComputer.RejectedJobException.class,
			action
		);
		assertEquals(expected, exception.reason());
	}

	private record Fixture(
		HolderLookup.Provider registries,
		Holder.Reference<NoiseGeneratorSettings> settings,
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises,
		NoiseSettings noiseSettings,
		TerrainDensityJob job
	) {
	}

	/** Separate authoritative traversal using the generated 26.2 NoiseChunk API. */
	private static final class AuthoritativeDensitySampler extends NoiseChunk {
		private static final Aquifer.FluidStatus EMPTY_FLUID = new Aquifer.FluidStatus(
			Integer.MIN_VALUE, Blocks.AIR.defaultBlockState()
		);
		private static final Aquifer.FluidPicker UNUSED_FLUID_PICKER = (x, y, z) -> EMPTY_FLUID;

		private final NoiseSettings noiseSettings;
		private final int chunkMinX;
		private final int chunkMinZ;

		private AuthoritativeDensitySampler(
			RandomState randomState,
			NoiseGeneratorSettings settings,
			NoiseSettings noiseSettings,
			int chunkMinX,
			int chunkMinZ
		) {
			super(
				TerrainDensityJob.CHUNK_SIDE / noiseSettings.getCellWidth(),
				randomState,
				chunkMinX,
				chunkMinZ,
				noiseSettings,
				Beardifier.EMPTY,
				settings,
				UNUSED_FLUID_PICKER,
				Blender.empty()
			);
			this.noiseSettings = noiseSettings;
			this.chunkMinX = chunkMinX;
			this.chunkMinZ = chunkMinZ;
		}

		private double[] sample() {
			int cellWidth = noiseSettings.getCellWidth();
			int cellHeight = noiseSettings.getCellHeight();
			int cellCountXZ = TerrainDensityJob.CHUNK_SIDE / cellWidth;
			int cellCountY = noiseSettings.height() / cellHeight;
			double[] densities = new double[TerrainDensityJob.CHUNK_SIDE * TerrainDensityJob.CHUNK_SIDE * noiseSettings.height()];
			initializeForFirstCellX();
			try {
				for (int cellX = 0; cellX < cellCountXZ; cellX++) {
					advanceCellX(cellX);
					for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
						for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
							selectCellYZ(cellY, cellZ);
							for (int yInCell = cellHeight - 1; yInCell >= 0; yInCell--) {
								int blockY = noiseSettings.minY() + cellY * cellHeight + yInCell;
								int yOffset = blockY - noiseSettings.minY();
								updateForY(blockY, (double)yInCell / cellHeight);
								for (int xInCell = 0; xInCell < cellWidth; xInCell++) {
									int xOffset = cellX * cellWidth + xInCell;
									updateForX(chunkMinX + xOffset, (double)xInCell / cellWidth);
									for (int zInCell = 0; zInCell < cellWidth; zInCell++) {
										int zOffset = cellZ * cellWidth + zInCell;
										updateForZ(chunkMinZ + zOffset, (double)zInCell / cellWidth);
										densities[(yOffset * TerrainDensityJob.CHUNK_SIDE + xOffset)
											* TerrainDensityJob.CHUNK_SIDE + zOffset] = getInterpolatedDensity();
									}
								}
							}
						}
					}
					swapSlices();
				}
			} finally {
				stopInterpolation();
			}
			return densities;
		}
	}
}
