package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
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

import io.github.genichimaruo.worldgenassist.client.SeededLeafClientTestBridge;
import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import io.github.genichimaruo.worldgenassist.network.SeededLeafTranscriptCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SeededLeafTraceTest {
	private static final long AUTHORITATIVE_SEED = 8675309L;
	private static final long DUMMY_REPLAY_SEED = -7046029254386353131L;
	private static final int MAX_TEST_TRACE_ENTRIES = SeededLeafTranscript.MAX_ENTRIES;

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void traceSessionIsNotLeaked() {
		assertFalse(SeededLeafTrace.hasActiveSession());
	}

	@Test
	void replaysNoiseChunkBitExactlyWithDifferentSeed() {
		Fixture fixture = fixture();
		SeededLeafTrace.Recording recording = SeededLeafTrace.beginRecording(
			fixture.authoritative().router(),
			MAX_TEST_TRACE_ENTRIES
		);
		double[] expected;
		try (recording) {
			expected = sample(fixture.authoritative(), fixture.settings());
		}
		SeededLeafTranscript trace = roundTripTranscript(recording.transcript());

		SeededLeafTrace.Replay replay = SeededLeafTrace.beginReplay(fixture.replay().router(), trace);
		double[] actual;
		try (replay) {
			actual = sample(fixture.replay(), fixture.settings());
		}

		assertFalse(trace.entries().isEmpty());
		assertEquals(2_548, trace.size());
		assertTrue(trace.entries().stream().anyMatch(entry -> entry.kind() == SeededLeafTranscript.Kind.NORMAL_NOISE));
		assertTrue(trace.entries().stream().anyMatch(entry -> entry.kind() == SeededLeafTranscript.Kind.BLENDED_NOISE));
		assertEquals(trace.size(), replay.consumedEntries());
		assertDensityBits(expected, actual);
	}

	@Test
	void replaysStandardHeightNegativeChunkBitExactlyWithDifferentSeed() {
		Fixture fixture = fixture();
		NoiseSettings fullHeight = fixture.settings().noiseSettings();
		SeededLeafTrace.Recording recording = SeededLeafTrace.beginRecording(
			fixture.authoritative().router(),
			MAX_TEST_TRACE_ENTRIES
		);
		double[] expected;
		try (recording) {
			expected = sample(fixture.authoritative(), fixture.settings(), fullHeight, -112, 80);
		}
		SeededLeafTranscript trace = roundTripTranscript(recording.transcript());

		SeededLeafTrace.Replay replay = SeededLeafTrace.beginReplay(fixture.replay().router(), trace);
		double[] actual;
		try (replay) {
			actual = sample(fixture.replay(), fixture.settings(), fullHeight, -112, 80);
		}

		assertEquals(17_972, trace.size());
		assertEquals(trace.size(), replay.consumedEntries());
		assertDensityBits(expected, actual);
	}

	@Test
	void rejectsChangedLeafInputBeforeReturningAValue() {
		Fixture fixture = fixture();
		SeededLeafTranscript trace = record(fixture);
		ArrayList<SeededLeafTranscript.Entry> changed = new ArrayList<>(trace.entries());
		SeededLeafTranscript.Entry first = changed.getFirst();
		changed.set(0, new SeededLeafTranscript.Entry(
			first.kind(),
			first.leafId(),
			first.xBits() ^ 1L,
			first.yBits(),
			first.zBits(),
			first.valueBits()
		));
		SeededLeafTranscript corrupted = new SeededLeafTranscript(changed);

		SeededLeafTrace.TraceMismatchException error = assertThrows(
			SeededLeafTrace.TraceMismatchException.class,
			() -> {
				try (SeededLeafTrace.Replay ignored = SeededLeafTrace.beginReplay(fixture.replay().router(), corrupted)) {
					sample(fixture.replay(), fixture.settings());
				}
			}
		);

		assertTrue(error.getMessage().contains("mismatch at entry 0"), error::getMessage);
	}

	@Test
	void rejectsRecordingBeyondConfiguredBound() {
		Fixture fixture = fixture();

		SeededLeafTrace.TraceMismatchException error = assertThrows(
			SeededLeafTrace.TraceMismatchException.class,
			() -> {
				try (SeededLeafTrace.Recording ignored = SeededLeafTrace.beginRecording(fixture.authoritative().router(), 1)) {
					sample(fixture.authoritative(), fixture.settings());
				}
			}
		);

		assertTrue(error.getMessage().contains("exceeded its bound"), error::getMessage);
	}

	@Test
	void rejectsUnconsumedTrailingEntries() {
		Fixture fixture = fixture();
		SeededLeafTranscript trace = record(fixture);
		ArrayList<SeededLeafTranscript.Entry> extended = new ArrayList<>(trace.entries());
		extended.add(trace.entries().getLast());

		SeededLeafTrace.TraceMismatchException error = assertThrows(
			SeededLeafTrace.TraceMismatchException.class,
			() -> {
				try (SeededLeafTrace.Replay ignored = SeededLeafTrace.beginReplay(
					fixture.replay().router(),
					new SeededLeafTranscript(extended)
				)) {
					sample(fixture.replay(), fixture.settings());
				}
			}
		);

		assertTrue(error.getMessage().contains("of " + extended.size() + " entries"), error::getMessage);
	}

	@Test
	@Timeout(10)
	void selectedRecordedLeafInterruptionCancelsActualRecorderTraversalAndTheNextTraversalSucceeds() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		NoiseSettings slice = testNoiseSettings();
		AtomicInteger selectedEntryObservations = new AtomicInteger();
		try (SeededLeafTrace.TestEntryObserverScope ignored = SeededLeafTrace.beginTestEntryObserver(entryCount -> {
			if (entryCount == 3) {
				selectedEntryObservations.incrementAndGet();
				Thread.currentThread().interrupt();
			}
		})) {
			assertThrows(CancellationException.class, () -> SeededLeafJobSpecRecorder.record(
				Level.OVERWORLD.identifier(),
				10,
				-20,
				RandomState.create(settings.value(), noises, AUTHORITATIVE_SEED),
				settings,
				slice,
				MAX_TEST_TRACE_ENTRIES
			));
		} finally {
			Thread.interrupted();
		}

		assertEquals(1, selectedEntryObservations.get(), "observer must interrupt at exactly the selected recorded leaf");
		assertFalse(SeededLeafTrace.hasActiveSession(), "cancelled recorder traversal must close its trace session");
		assertEquals(4_096, SeededLeafJobSpecRecorder.record(
			Level.OVERWORLD.identifier(),
			10,
			-20,
			RandomState.create(settings.value(), noises, AUTHORITATIVE_SEED),
			settings,
			slice,
			MAX_TEST_TRACE_ENTRIES
		).sampledDensities(), "a later recorder traversal must not retain the interrupted observer");
		assertFalse(SeededLeafTrace.hasActiveSession());
	}

	@Test
	@Timeout(10)
	void selectedReplayedLeafInterruptionCancelsActualClientTraversalAndTheNextTraversalSucceeds() {
		ClientReplayFixture fixture = clientReplayFixture();
		AtomicInteger selectedEntryObservations = new AtomicInteger();
		try (SeededLeafTrace.TestEntryObserverScope ignored = SeededLeafTrace.beginTestEntryObserver(entryCount -> {
			if (entryCount == 5) {
				selectedEntryObservations.incrementAndGet();
				Thread.currentThread().interrupt();
			}
		})) {
			assertThrows(CancellationException.class, () -> SeededLeafClientTestBridge.compute(
				fixture.registries(), Level.OVERWORLD.identifier(), fixture.authorization()
			));
		} finally {
			Thread.interrupted();
		}

		assertEquals(1, selectedEntryObservations.get(), "observer must interrupt at exactly the selected replayed leaf");
		assertFalse(SeededLeafTrace.hasActiveSession(), "cancelled client traversal must close its replay session");
		SeededLeafDensityResult result = SeededLeafClientTestBridge.compute(
			fixture.registries(), Level.OVERWORLD.identifier(), fixture.authorization()
		);
		assertEquals(fixture.sampleCount(), result.densityCount(), "a later client traversal must not retain the interrupted observer");
		assertFalse(SeededLeafTrace.hasActiveSession());
	}

	@Test
	@Timeout(5)
	void testEntryObserverScopeIsNonNestedThreadConfinedAndDoesNotRetainAfterClose() {
		Fixture fixture = fixture();
		AtomicInteger observations = new AtomicInteger();
		SeededLeafTrace.TestEntryObserverScope scope = SeededLeafTrace.beginTestEntryObserver(count -> observations.incrementAndGet());
		try {
			assertThrows(IllegalStateException.class, () -> SeededLeafTrace.beginTestEntryObserver(count -> { }));
			AtomicReference<Throwable> crossThreadFailure = new AtomicReference<>();
			Thread otherThread = new Thread(() -> {
				try {
					scope.close();
				} catch (Throwable failure) {
					crossThreadFailure.set(failure);
				}
			}, "CAWG-SeededLeafTraceTest-close");
			otherThread.start();
			awaitThreadExit(otherThread);
			assertTrue(crossThreadFailure.get() instanceof IllegalStateException);
		} finally {
			scope.close();
		}

		record(fixture);
		assertEquals(0, observations.get(), "closed observer must not retain or observe later trace entries");
		try (SeededLeafTrace.TestEntryObserverScope ignored = SeededLeafTrace.beginTestEntryObserver(count -> { })) {
			// Opening a replacement scope proves close removed the thread-local observer.
		}
	}

	private static SeededLeafTranscript record(Fixture fixture) {
		SeededLeafTrace.Recording recording = SeededLeafTrace.beginRecording(
			fixture.authoritative().router(),
			MAX_TEST_TRACE_ENTRIES
		);
		try (recording) {
			sample(fixture.authoritative(), fixture.settings());
		}
		return recording.transcript();
	}

	private static ClientReplayFixture clientReplayFixture() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		SeededLeafJobSpecRecorder.RecordedSpec recorded = SeededLeafJobSpecRecorder.record(
			Level.OVERWORLD.identifier(),
			10,
			-20,
			RandomState.create(settings.value(), noises, AUTHORITATIVE_SEED),
			settings,
			testNoiseSettings(),
			MAX_TEST_TRACE_ENTRIES
		);
		AuthorizedSeededLeafJob authorization = new AuthorizedSeededLeafJob(
			new SeededLeafJob(
				UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
				OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
				recorded.spec()
			),
			SeededLeafJobAuthenticationTag.fromHex("cd".repeat(SeededLeafJobAuthenticationTag.BYTE_LENGTH))
		);
		return new ClientReplayFixture(registries, authorization, recorded.spec().sampleCount());
	}

	private static void awaitThreadExit(Thread thread) {
		try {
			thread.join(1_000L);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for test thread", interrupted);
		}
		assertFalse(thread.isAlive(), "test thread did not exit");
	}

	private static SeededLeafTranscript roundTripTranscript(SeededLeafTranscript transcript) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			SeededLeafTranscriptCodec.CODEC.encode(buffer, transcript);
			assertTrue(buffer.readableBytes() <= SeededLeafTranscriptCodec.MAX_ENCODED_BYTES);
			SeededLeafTranscript decoded = SeededLeafTranscriptCodec.CODEC.decode(buffer);
			assertEquals(0, buffer.readableBytes());
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private static Fixture fixture() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		return new Fixture(
			settings.value(),
			RandomState.create(settings.value(), noises, AUTHORITATIVE_SEED),
			RandomState.create(settings.value(), noises, DUMMY_REPLAY_SEED)
		);
	}

	private static double[] sample(RandomState randomState, NoiseGeneratorSettings settings) {
		return sample(randomState, settings, testNoiseSettings(), 0, 0);
	}

	private static double[] sample(
		RandomState randomState,
		NoiseGeneratorSettings settings,
		NoiseSettings noiseSettings,
		int chunkMinX,
		int chunkMinZ
	) {
		return new TestSampler(randomState, settings, noiseSettings, chunkMinX, chunkMinZ).sample();
	}

	private static NoiseSettings testNoiseSettings() {
		return NoiseSettings.create(-64, 16, 1, 2);
	}

	private static void assertDensityBits(double[] expected, double[] actual) {
		assertEquals(expected.length, actual.length);
		for (int index = 0; index < expected.length; index++) {
			assertEquals(
				Double.doubleToRawLongBits(expected[index]),
				Double.doubleToRawLongBits(actual[index]),
				"density bits at index " + index
			);
		}
	}

	private record Fixture(NoiseGeneratorSettings settings, RandomState authoritative, RandomState replay) {
	}

	private record ClientReplayFixture(
		HolderLookup.Provider registries,
		AuthorizedSeededLeafJob authorization,
		int sampleCount
	) {
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
