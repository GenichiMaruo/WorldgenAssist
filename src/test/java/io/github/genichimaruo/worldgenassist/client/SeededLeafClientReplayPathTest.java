package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

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
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import io.github.genichimaruo.worldgenassist.network.AuthorizedSeededLeafJobCodec;
import io.github.genichimaruo.worldgenassist.network.SeededLeafDensityResultEnvelopeCodec;
import io.github.genichimaruo.worldgenassist.server.SeededLeafJobSpecRecorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises the client-visible half of the seed-free path with a real recorded transcript. */
class SeededLeafClientReplayPathTest {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void recordedTranscriptRoundTripsThroughAuthorizationAndResultCodecsUsingDummySeed() {
		Fixture fixture = fixture();
		HolderLookup.Provider registries = fixture.registries();
		SeededLeafJobSpecRecorder.RecordedSpec recorded = fixture.recorded();
		AuthorizedSeededLeafJob authorization = fixture.authorization();

		AuthorizedSeededLeafJob decodedAuthorization = roundTripAuthorization(authorization);
		SeededLeafDensityResult clientResult = SeededLeafClientDensityComputer.compute(registries, OVERWORLD, decodedAuthorization);
		SeededLeafDensityResultEnvelope decodedEnvelope = roundTripEnvelope(
			SeededLeafDensityResultEnvelope.encode(clientResult)
		);
		SeededLeafDensityResult decodedResult = decodedEnvelope.decode();

		assertEquals(recorded.spec().sampleCount(), recorded.sampledDensities());
		assertEquals(recorded.spec().sampleCount(), decodedResult.densityCount());
		for (int index = 0; index < clientResult.densityCount(); index++) {
			assertEquals(
				Double.doubleToRawLongBits(clientResult.densityAt(index)),
				Double.doubleToRawLongBits(decodedResult.densityAt(index)),
				"density bits at index " + index
			);
		}
	}

	@Test
	void transcriptMutationTruncationExtraInputKindAndIncompleteReplayFailClosed() {
		Fixture fixture = fixture();
		SeededLeafTranscript transcript = fixture.authorization().job().transcript();
		SeededLeafTranscript.Entry first = transcript.entries().getFirst();
		List<SeededLeafTranscript> invalidTranscripts = List.of(
			new SeededLeafTranscript(transcript.entries().subList(0, transcript.size() - 1)),
			withExtraEntry(transcript),
			withFirstEntry(transcript, new SeededLeafTranscript.Entry(
				first.kind(), first.leafId(), first.xBits() ^ 1L, first.yBits(), first.zBits(), first.valueBits()
			)),
			withFirstEntry(transcript, new SeededLeafTranscript.Entry(
				first.kind() == SeededLeafTranscript.Kind.NORMAL_NOISE
					? SeededLeafTranscript.Kind.BLENDED_NOISE : SeededLeafTranscript.Kind.NORMAL_NOISE,
				first.leafId(), first.xBits(), first.yBits(), first.zBits(), first.valueBits()
			)),
			withFirstEntry(transcript, new SeededLeafTranscript.Entry(
				first.kind(), "minecraft:unassigned_test_leaf", first.xBits(), first.yBits(), first.zBits(), first.valueBits()
			))
		);
		for (SeededLeafTranscript invalid : invalidTranscripts) {
			SeededLeafClientDensityComputer.RejectedJobException rejected = assertThrows(
				SeededLeafClientDensityComputer.RejectedJobException.class,
				() -> SeededLeafClientDensityComputer.compute(fixture.registries(), OVERWORLD,
					authorizationWithTranscript(fixture.authorization(), invalid))
			);
			assertEquals("transcript_mismatch", rejected.reason());
		}
	}

	@Test
	void clientSamplerRejectsInvalidAndMismatchedGeometryBeforeTraversal() {
		NoiseSettings validSlice = NoiseSettings.create(-64, 16, 1, 2);
		assertThrows(IllegalArgumentException.class,
			() -> new ClientDensitySampler(0, 0, -64, 16, 0, 8, null, null, validSlice));
		assertThrows(IllegalArgumentException.class,
			() -> new ClientDensitySampler(0, 0, -64, 16, 3, 8, null, null, validSlice));
		assertThrows(IllegalArgumentException.class,
			() -> new ClientDensitySampler(0, 0, -63, 16, 4, 8, null, null, validSlice));

		Fixture fixture = fixture();
		Holder.Reference<NoiseGeneratorSettings> settings = fixture.registries().lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = fixture.registries().lookupOrThrow(Registries.NOISE);
		RandomState replay = RandomState.create(settings.value(), noises, SeededLeafClientDensityComputer.PUBLIC_DUMMY_SEED);
		assertThrows(IllegalArgumentException.class, () -> new ClientDensitySampler(
			0, 0, -64, 16, 8, 8, replay, settings.value(), NoiseSettings.create(-64, 16, 1, 2)
		));
	}

	@Test
	void clientReplayObservesCancellationBeforeTraversal() {
		Fixture fixture = fixture();
		Thread.currentThread().interrupt();
		try {
			assertThrows(CancellationException.class, () -> SeededLeafClientDensityComputer.compute(
				fixture.registries(), OVERWORLD, fixture.authorization()
			));
		} finally {
			Thread.interrupted();
		}
	}

	private static Fixture fixture() {
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		Holder.Reference<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
		NoiseSettings slice = NoiseSettings.create(-64, 16, 1, 2);
		RandomState authoritative = RandomState.create(settings.value(), noises, 8675309L);
		SeededLeafJobSpecRecorder.RecordedSpec recorded = SeededLeafJobSpecRecorder.record(
			OVERWORLD, 10, -20, authoritative, settings, slice, 32_768
		);
		AuthorizedSeededLeafJob authorization = new AuthorizedSeededLeafJob(
			new SeededLeafJob(
				UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
				OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
				recorded.spec()
			),
			SeededLeafJobAuthenticationTag.fromHex("cd".repeat(SeededLeafJobAuthenticationTag.BYTE_LENGTH))
		);
		return new Fixture(registries, recorded, authorization);
	}

	private static SeededLeafTranscript withExtraEntry(SeededLeafTranscript transcript) {
		ArrayList<SeededLeafTranscript.Entry> entries = new ArrayList<>(transcript.entries());
		entries.add(entries.getLast());
		return new SeededLeafTranscript(entries);
	}

	private static SeededLeafTranscript withFirstEntry(
		SeededLeafTranscript transcript,
		SeededLeafTranscript.Entry replacement
	) {
		ArrayList<SeededLeafTranscript.Entry> entries = new ArrayList<>(transcript.entries());
		entries.set(0, replacement);
		return new SeededLeafTranscript(entries);
	}

	private static AuthorizedSeededLeafJob authorizationWithTranscript(
		AuthorizedSeededLeafJob authorization,
		SeededLeafTranscript transcript
	) {
		SeededLeafJob job = authorization.job();
		return new AuthorizedSeededLeafJob(new SeededLeafJob(
			job.jobId(), job.contextId(), job.dimension(), job.chunkX(), job.chunkZ(), job.noiseSettings(),
			job.minY(), job.height(), job.cellWidth(), job.cellHeight(), transcript
		), authorization.authenticationTag());
	}

	private record Fixture(
		HolderLookup.Provider registries,
		SeededLeafJobSpecRecorder.RecordedSpec recorded,
		AuthorizedSeededLeafJob authorization
	) {
	}

	private static AuthorizedSeededLeafJob roundTripAuthorization(AuthorizedSeededLeafJob authorization) {
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

	private static SeededLeafDensityResultEnvelope roundTripEnvelope(SeededLeafDensityResultEnvelope envelope) {
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
}
