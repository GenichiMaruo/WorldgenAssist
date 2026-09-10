package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
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

import io.github.genichimaruo.worldgenassist.client.SeededLeafClientTestBridge;
import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.network.AuthorizedSeededLeafJobCodec;
import io.github.genichimaruo.worldgenassist.network.SeededLeafDensityResultEnvelopeCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Complete local protocol-v3 candidate chain; intentionally has no transport. */
class SeededLeafEndToEndTest {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
	private static final UUID OWNER = UUID.fromString("6517b949-2bd6-4c60-b278-649f450626ea");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@Timeout(30)
	void threePublicSeedsAcrossCoordinatesRoundTripThroughAuthorityClientExecutorAndField() throws Exception {
		long[] publicSeeds = {8675309L, 123456789L, -987654321L};
		int[][] chunks = {{10, -20}, {-11, 7}, {1_874_999, -1_874_999}};
		for (int index = 0; index < publicSeeds.length; index++) {
			Chain chain = recordAndAuthorize(publicSeeds[index], chunks[index][0], chunks[index][1]);
			try (SeededLeafResultValidationExecutor executor = new SeededLeafResultValidationExecutor(
				chain.authority, 1, RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS, Duration.ofSeconds(5)
			)) {
				executor.connectOwner(OWNER);
				AuthorizedSeededLeafJob decodedAuthorization = authorizationRoundTrip(chain.authorization);
				SeededLeafDensityResult clientResult = SeededLeafClientTestBridge.compute(
					chain.registries, OVERWORLD, decodedAuthorization
				);
				SeededLeafDensityResultEnvelope envelope = envelopeRoundTrip(SeededLeafDensityResultEnvelope.encode(clientResult));
				assertEquals(SeededLeafDensityResultEnvelope.Encoding.DEFLATE, envelope.encoding());
				SeededLeafResultValidationExecutor.Outcome outcome = executor.submit(
					OWNER, envelope, chain.validationContext
				).get(10, TimeUnit.SECONDS);
				assertEquals(SeededLeafResultValidationExecutor.Status.VALIDATED, outcome.status());
				assertEquals(32, outcome.sampledCells());
				assertEquals(4_096, outcome.sampledValues());
				RemoteDensityField field = RemoteDensityField.fromValidated(outcome, chain.authority);
				for (int density = 0; density < clientResult.densityCount(); density++) {
					int y = density / 256;
					int x = (density / 16) % 16;
					int z = density % 16;
					assertEquals(Double.doubleToRawLongBits(clientResult.densityAt(density)),
						Double.doubleToRawLongBits(field.densityAtOffset(x, y, z)), "seed/chunk density=" + density);
				}
			}
		}
	}

	@Test
	@Timeout(90)
	void standardHeightChainValidatesDeflateAndForcedRawEnvelopesWithoutRetainingTranscriptState() throws Exception {
		NoiseSettings standardHeight = overworldSettings().value().noiseSettings();
		long[] publicSeeds = {8675309L, 123456789L, -987654321L};
		int[][] chunks = {{-112, 80}, {11, -8}, {1_874_999, -1_874_999}};
		for (int fixture = 0; fixture < publicSeeds.length; fixture++) {
			verifyStandardHeightChain(standardHeight, publicSeeds[fixture], chunks[fixture][0], chunks[fixture][1]);
		}
	}

	private static void verifyStandardHeightChain(NoiseSettings standardHeight, long seed, int chunkX, int chunkZ) throws Exception {
		Chain chain = recordAndAuthorize(seed, chunkX, chunkZ, standardHeight);
		try (SeededLeafResultValidationExecutor executor = new SeededLeafResultValidationExecutor(
			chain.authority, 1, RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS, Duration.ofSeconds(10)
		)) {
			executor.connectOwner(OWNER);
			SeededLeafDensityResult deflateResult = SeededLeafClientTestBridge.compute(
				chain.registries, OVERWORLD, authorizationRoundTrip(chain.authorization)
			);
			SeededLeafDensityResultEnvelope deflate = envelopeRoundTrip(SeededLeafDensityResultEnvelope.encode(deflateResult));
			assertEquals(SeededLeafDensityResultEnvelope.Encoding.DEFLATE, deflate.encoding());
			RemoteDensityField deflateField = validatedField(executor, deflate, chain);
			double[] authoritative = new AuthoritativeDensitySampler(
				chain.validationContext.randomState(), chain.validationContext.settings(), standardHeight,
				chunkX * 16, chunkZ * 16).sample();
			assertDensityBits(authoritative, deflateResult);
			assertDensityBits(deflateResult, deflateField);
			awaitIdle(executor);

			AuthorizedSeededLeafJob rawAuthorization = chain.authority.tryIssue(OWNER, chain.authorization.job().spec())
				.authorization().orElseThrow();
			SeededLeafDensityResult rawResult = SeededLeafClientTestBridge.compute(chain.registries, OVERWORLD, rawAuthorization);
			SeededLeafDensityResultEnvelope raw = envelopeRoundTrip(rawEnvelope(rawResult));
			assertEquals(SeededLeafDensityResultEnvelope.Encoding.RAW, raw.encoding());
			RemoteDensityField rawField = validatedField(executor, raw, chain);
			assertDensityBits(authoritative, rawResult);
			assertDensityBits(rawResult, rawField);

			assertNoTranscriptOrProtocolV2Identity(SeededLeafDensityResult.class);
			assertNoTranscriptOrProtocolV2Identity(SeededLeafResultValidationExecutor.Outcome.class);
			assertNoTranscriptOrProtocolV2Identity(RemoteDensityField.class);
		}
	}

	@Test
	void recorderRejectsDirectOrNonOverworldHoldersAndMismatchedGeometryBeforeTraversal() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> overworld = overworldSettings(registries);
		Holder.Reference<NoiseGeneratorSettings> nether = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.NETHER);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		RandomState authoritative = RandomState.create(overworld.value(), noises, 8675309L);
		NoiseSettings validClampedSlice = NoiseSettings.create(-64, 16, 1, 2);

		assertEquals(4_096, SeededLeafJobSpecRecorder.record(
			OVERWORLD, 10, -20, authoritative, overworld, validClampedSlice, 32_768
		).sampledDensities());
		assertThrows(IllegalArgumentException.class, () -> SeededLeafJobSpecRecorder.record(
			OVERWORLD, 10, -20, authoritative, Holder.direct(overworld.value()), validClampedSlice, 32_768
		));
		assertThrows(IllegalArgumentException.class, () -> SeededLeafJobSpecRecorder.record(
			OVERWORLD, 10, -20, authoritative, nether, validClampedSlice, 32_768
		));
		assertThrows(IllegalArgumentException.class, () -> SeededLeafJobSpecRecorder.record(
			OVERWORLD, 10, -20, authoritative, overworld, NoiseSettings.create(-64, 16, 2, 2), 32_768
		));
	}

	@Test
	void recorderObservesCancellationBeforeTraversing() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = overworldSettings(registries);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		RandomState authoritative = RandomState.create(settings.value(), noises, 8675309L);
		Thread.currentThread().interrupt();
		try {
			assertThrows(CancellationException.class, () -> SeededLeafJobSpecRecorder.record(
				OVERWORLD, 10, -20, authoritative, settings, NoiseSettings.create(-64, 16, 1, 2), 32_768
			));
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void recorderRejectsMisalignedGeometryBeforeOpeningItsTraceSession() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = overworldSettings(registries);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		RandomState authoritative = RandomState.create(settings.value(), noises, 8675309L);
		try (SeededLeafTrace.Recording ignored = SeededLeafTrace.beginRecording(authoritative.router(), 1)) {
			IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> SeededLeafJobSpecRecorder.record(
				OVERWORLD, 10, -20, authoritative, settings, new NoiseSettings(-63, 16, 1, 2), 32_768
			));
			assertTrue(rejected.getMessage().contains("Minimum Y"));
		}
	}

	@Test
	@Timeout(15)
	void oneUlpMutationIsRejectedByTheRealExecutorAndReloadMakesSuccessStale() throws Exception {
		Chain chain = recordAndAuthorize(8675309L, 10, -20);
		try (SeededLeafResultValidationExecutor executor = new SeededLeafResultValidationExecutor(
			chain.authority, 1, RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS, Duration.ofSeconds(5)
		)) {
			executor.connectOwner(OWNER);
			SeededLeafDensityResult result = SeededLeafClientTestBridge.compute(chain.registries, OVERWORLD, chain.authorization);
			double[] altered = result.densities();
			altered[123] = Math.nextUp(altered[123]);
			SeededLeafDensityResultEnvelope alteredEnvelope = SeededLeafDensityResultEnvelope.encode(
				new SeededLeafDensityResult(result.claim(), altered, result.clientComputeNanos())
			);
			SeededLeafResultValidationExecutor.Outcome rejected = executor.submit(
				OWNER, alteredEnvelope, chain.validationContext
			).get(10, TimeUnit.SECONDS);
			assertEquals(SeededLeafResultValidationExecutor.Status.VALIDATION_FAILED, rejected.status());
			assertTrue(rejected.acceptedResult().isEmpty(), "a one-ULP rejection must not expose an accepted result");

			Chain success = recordAndAuthorize(chain.registries, 8675309L, 11, -20, chain.authority);
			SeededLeafDensityResult successResult = SeededLeafClientTestBridge.compute(chain.registries, OVERWORLD, success.authorization);
			SeededLeafResultValidationExecutor.Outcome outcome = executor.submit(
				OWNER, SeededLeafDensityResultEnvelope.encode(successResult), chain.validationContext
			).get(10, TimeUnit.SECONDS);
			assertEquals(SeededLeafResultValidationExecutor.Status.VALIDATED, outcome.status());
			chain.authority.reload();
			assertThrows(IllegalStateException.class, () -> RemoteDensityField.fromValidated(outcome, chain.authority));
		}
	}

	private static Chain recordAndAuthorize(long seed, int chunkX, int chunkZ) {
		return recordAndAuthorize(null, seed, chunkX, chunkZ, null);
	}

	private static Chain recordAndAuthorize(long seed, int chunkX, int chunkZ, NoiseSettings noiseSettings) {
		return recordAndAuthorize(null, seed, chunkX, chunkZ, null, noiseSettings);
	}

	private static Chain recordAndAuthorize(
		HolderLookup.Provider suppliedRegistries, long seed, int chunkX, int chunkZ, SeededLeafJobAuthority suppliedAuthority
	) {
		return recordAndAuthorize(suppliedRegistries, seed, chunkX, chunkZ, suppliedAuthority, NoiseSettings.create(-64, 16, 1, 2));
	}

	private static Chain recordAndAuthorize(
		HolderLookup.Provider suppliedRegistries,
		long seed,
		int chunkX,
		int chunkZ,
		SeededLeafJobAuthority suppliedAuthority,
		NoiseSettings noiseSettings
	) {
		HolderLookup.Provider registries = suppliedRegistries == null ? VanillaRegistries.createLookup() : suppliedRegistries;
		Holder.Reference<NoiseGeneratorSettings> settings = overworldSettings(registries);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		RandomState authoritative = RandomState.create(settings.value(), noises, seed);
		SeededLeafJobSpecRecorder.RecordedSpec recorded = SeededLeafJobSpecRecorder.record(
			OVERWORLD, chunkX, chunkZ, authoritative, settings, noiseSettings, 100_000
		);
		SeededLeafJobAuthority authority = suppliedAuthority == null ? authority() : suppliedAuthority;
		AuthorizedSeededLeafJob authorization = authority.tryIssue(OWNER, recorded.spec()).authorization().orElseThrow();
		return new Chain(registries, authority, authorization,
			new SeededLeafResultValidationExecutor.ValidationContext(authoritative, settings.value(), noiseSettings));
	}

	private static Holder.Reference<NoiseGeneratorSettings> overworldSettings() {
		return overworldSettings(VanillaRegistries.createLookup());
	}

	private static Holder.Reference<NoiseGeneratorSettings> overworldSettings(HolderLookup.Provider registries) {
		return registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
	}

	private static SeededLeafDensityResultEnvelope rawEnvelope(SeededLeafDensityResult result) {
		ByteBuffer raw = ByteBuffer.allocate(result.densityCount() * Double.BYTES).order(ByteOrder.BIG_ENDIAN);
		for (int index = 0; index < result.densityCount(); index++) {
			raw.putDouble(result.densityAt(index));
		}
		return new SeededLeafDensityResultEnvelope(
			result.claim(), result.densityCount(), SeededLeafDensityResultEnvelope.Encoding.RAW,
			raw.array(), result.clientComputeNanos(), 0L
		);
	}

	private static RemoteDensityField validatedField(
		SeededLeafResultValidationExecutor executor,
		SeededLeafDensityResultEnvelope envelope,
		Chain chain
	) throws Exception {
		SeededLeafResultValidationExecutor.Outcome outcome = executor.submit(OWNER, envelope, chain.validationContext)
			.get(20, TimeUnit.SECONDS);
		assertEquals(SeededLeafResultValidationExecutor.Status.VALIDATED, outcome.status());
		assertEquals(64, outcome.sampledCells(), "standard-height executor coverage is sampled, not all 768 cells");
		assertEquals(8_192, outcome.sampledValues());
		return RemoteDensityField.fromValidated(outcome, chain.authority);
	}

	private static void awaitIdle(SeededLeafResultValidationExecutor executor) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (executor.pendingCount() != 0 && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertEquals(0, executor.pendingCount());
	}

	private static void assertDensityBits(SeededLeafDensityResult result, RemoteDensityField field) {
		for (int density = 0; density < result.densityCount(); density++) {
			int y = density / 256;
			int x = (density / 16) % 16;
			int z = density % 16;
			assertEquals(Double.doubleToRawLongBits(result.densityAt(density)),
				Double.doubleToRawLongBits(field.densityAtOffset(x, y, z)), "density=" + density);
		}
	}

	private static void assertDensityBits(double[] expected, SeededLeafDensityResult actual) {
		assertEquals(expected.length, actual.densityCount());
		for (int density = 0; density < expected.length; density++) {
			assertEquals(Double.doubleToRawLongBits(expected[density]), Double.doubleToRawLongBits(actual.densityAt(density)),
				"authoritative density=" + density);
		}
	}

	private static void assertNoTranscriptOrProtocolV2Identity(Class<?> type) {
		assertTrue(Arrays.stream(type.getDeclaredFields()).noneMatch(field ->
			field.getType().getName().contains("SeededLeafTranscript")
				|| field.getType().getName().contains("TerrainJobIdentity")
				|| field.getName().toLowerCase().contains("seed")
				|| field.getName().toLowerCase().contains("fingerprint")
		), () -> "sensitive retained field in " + type.getName());
	}

	private static SeededLeafJobAuthority authority() {
		AtomicInteger sequence = new AtomicInteger();
		byte[] key = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		Arrays.fill(key, (byte)0x5a);
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
			8, 8, 100_000, 100_000, Duration.ofSeconds(30), Duration.ofSeconds(30), System::nanoTime,
			() -> new UUID(0L, sequence.incrementAndGet()),
			() -> OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			() -> SeededLeafJobAuthenticator.fromKey(key)
		);
		authority.start();
		return authority;
	}

	private static AuthorizedSeededLeafJob authorizationRoundTrip(AuthorizedSeededLeafJob authorization) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			AuthorizedSeededLeafJobCodec.CODEC.encode(buffer, authorization);
			AuthorizedSeededLeafJob decoded = AuthorizedSeededLeafJobCodec.CODEC.decode(buffer);
			assertEquals(0, buffer.readableBytes());
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private static SeededLeafDensityResultEnvelope envelopeRoundTrip(SeededLeafDensityResultEnvelope envelope) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			SeededLeafDensityResultEnvelopeCodec.CODEC.encode(buffer, envelope);
			SeededLeafDensityResultEnvelope decoded = SeededLeafDensityResultEnvelopeCodec.CODEC.decode(buffer);
			assertEquals(0, buffer.readableBytes());
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private record Chain(
		HolderLookup.Provider registries,
		SeededLeafJobAuthority authority,
		AuthorizedSeededLeafJob authorization,
		SeededLeafResultValidationExecutor.ValidationContext validationContext
	) {
	}

	/** Separate authoritative traversal for complete standard-height bit comparison. */
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
			super(16 / noiseSettings.getCellWidth(), randomState, chunkMinX, chunkMinZ, noiseSettings,
				Beardifier.EMPTY, settings, UNUSED_FLUID_PICKER, Blender.empty());
			this.noiseSettings = noiseSettings;
			this.chunkMinX = chunkMinX;
			this.chunkMinZ = chunkMinZ;
		}

		private double[] sample() {
			int cellWidth = noiseSettings.getCellWidth();
			int cellHeight = noiseSettings.getCellHeight();
			int cellsXZ = 16 / cellWidth;
			int cellsY = noiseSettings.height() / cellHeight;
			double[] densities = new double[16 * 16 * noiseSettings.height()];
			initializeForFirstCellX();
			try {
				for (int cellX = 0; cellX < cellsXZ; cellX++) {
					advanceCellX(cellX);
					for (int cellZ = 0; cellZ < cellsXZ; cellZ++) {
						for (int cellY = cellsY - 1; cellY >= 0; cellY--) {
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
										densities[(yOffset * 16 + xOffset) * 16 + zOffset] = getInterpolatedDensity();
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
