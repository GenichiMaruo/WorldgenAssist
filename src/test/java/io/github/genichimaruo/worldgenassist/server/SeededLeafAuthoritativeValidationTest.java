package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobSpec;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeededLeafAuthoritativeValidationTest {
	private static final UUID OWNER = UUID.fromString("16539a37-f40d-4b02-a378-8dc17c7b22db");
	private static final long WORLD_SEED = 8675309L;
	private static final int CHUNK_X = 10;
	private static final int CHUNK_Z = -20;

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void gatedSeedFreeResultPassesIndependentNoiseChunkValidationAndCorruptionFails() {
		Fixture fixture = fixture();
		NoiseSettings noise = NoiseSettings.create(-64, 16, 1, 2);
		double[] exact = new TestSampler(
			fixture.randomState,
			fixture.settings,
			noise,
			CHUNK_X * 16,
			CHUNK_Z * 16
		).sample();

		SeededLeafDensityResultGate.AcceptedResult accepted = gate(exact);
		RemoteDensityValidator.ValidationMetrics metrics = RemoteDensityValidator.validate(
			accepted,
			RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS,
			fixture.randomState,
			fixture.settings,
			noise,
			new Random(123L)
		);
		assertEquals(32, metrics.sampledCells());
		assertEquals(exact.length, metrics.sampledValues());

		double[] corrupted = exact.clone();
		corrupted[123] = Math.nextUp(corrupted[123]);
		SeededLeafDensityResultGate.AcceptedResult bad = gate(corrupted);
		assertThrows(
			RemoteDensityValidator.RemoteDensityValidationException.class,
			() -> RemoteDensityValidator.validate(
				bad,
				RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS,
				fixture.randomState,
				fixture.settings,
				noise,
				new Random(123L)
			)
		);
	}

	@Test
	void rejectsGeometryThatDoesNotMatchAuthoritativeNoiseSettings() {
		Fixture fixture = fixture();
		SeededLeafDensityResultGate.AcceptedResult accepted = gate(new double[16 * 16 * 16]);
		NoiseSettings wrongCellWidth = NoiseSettings.create(-64, 16, 2, 2);

		assertThrows(
			RemoteDensityValidator.RemoteDensityValidationException.class,
			() -> RemoteDensityValidator.validate(
				accepted,
				1,
				fixture.randomState,
				fixture.settings,
				wrongCellWidth,
				new Random(123L)
			)
		);
	}

	private static SeededLeafDensityResultGate.AcceptedResult gate(double[] densities) {
		byte[] key = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		Arrays.fill(key, (byte)0x11);
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
			1,
			1,
			10,
			10,
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			System::nanoTime,
			() -> UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			() -> OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			() -> SeededLeafJobAuthenticator.fromKey(key)
		);
		authority.start();
		AuthorizedSeededLeafJob authorization = authority.tryIssue(OWNER, spec()).authorization().orElseThrow();
		SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(authorization);
		SeededLeafDensityResultEnvelope envelope = SeededLeafDensityResultEnvelope.encode(
			new SeededLeafDensityResult(claim, densities, 0L)
		);
		return SeededLeafDensityResultGate.accept(authority, OWNER, envelope).acceptedResult().orElseThrow();
	}

	private static SeededLeafJobSpec spec() {
		return new SeededLeafJobSpec(
			Identifier.parse("minecraft:overworld"),
			CHUNK_X,
			CHUNK_Z,
			Identifier.parse("minecraft:overworld"),
			-64,
			16,
			4,
			8,
			new SeededLeafTranscript(List.of(new SeededLeafTranscript.Entry(
				SeededLeafTranscript.Kind.NORMAL_NOISE,
				"normal:minecraft:test",
				1L,
				2L,
				3L,
				4L
			)))
		);
	}

	private static Fixture fixture() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		return new Fixture(settings.value(), RandomState.create(settings.value(), noises, WORLD_SEED));
	}

	private record Fixture(NoiseGeneratorSettings settings, RandomState randomState) {
	}

	private static final class TestSampler extends NoiseChunk {
		private static final Aquifer.FluidStatus EMPTY_FLUID = new Aquifer.FluidStatus(
			Integer.MIN_VALUE,
			Blocks.AIR.defaultBlockState()
		);
		private static final Aquifer.FluidPicker UNUSED_FLUID_PICKER = (x, y, z) -> EMPTY_FLUID;

		private final NoiseSettings noiseSettings;
		private final int chunkMinX;
		private final int chunkMinZ;

		private TestSampler(
			RandomState randomState,
			NoiseGeneratorSettings settings,
			NoiseSettings noiseSettings,
			int chunkMinX,
			int chunkMinZ
		) {
			super(
				16 / noiseSettings.getCellWidth(),
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
			int cellCountXZ = 16 / cellWidth;
			int cellCountY = noiseSettings.height() / cellHeight;
			double[] densities = new double[16 * 16 * noiseSettings.height()];
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
										int index = (yOffset * 16 + xOffset) * 16 + zOffset;
										densities[index] = getInterpolatedDensity();
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
