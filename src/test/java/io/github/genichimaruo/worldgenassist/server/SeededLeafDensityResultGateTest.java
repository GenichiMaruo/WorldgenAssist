package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobSpec;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeededLeafDensityResultGateTest {
	private static final UUID OWNER = UUID.fromString("16539a37-f40d-4b02-a378-8dc17c7b22db");
	private static final UUID OTHER_OWNER = UUID.fromString("4f4b52e7-c70d-42aa-93de-a14fc1145153");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void authenticatesOnceThenReturnsOnlyGeometryAndValidatedValues() {
		Fixture fixture = fixture();
		double[] densities = new double[TerrainDensityJob.MAX_SAMPLE_COUNT];
		densities[123] = -0.25;
		SeededLeafDensityResultEnvelope envelope = SeededLeafDensityResultEnvelope.encode(
			new SeededLeafDensityResult(fixture.claim, densities, 50L)
		);

		SeededLeafDensityResultGate.GateResult accepted = SeededLeafDensityResultGate.accept(
			fixture.authority, OWNER, envelope
		);
		assertEquals(SeededLeafDensityResultGate.Status.ACCEPTED_FOR_AUTHORITATIVE_VALIDATION, accepted.status());
		assertEquals(SeededLeafJobAuthority.ClaimStatus.ACCEPTED, accepted.claimStatus());
		SeededLeafDensityResultGate.AcceptedResult result = accepted.acceptedResult().orElseThrow();
		assertEquals(fixture.authorization.job().jobId(), result.jobId());
		assertEquals(fixture.authorization.job().contextId(), result.contextId());
		assertEquals(10, result.chunkX());
		assertEquals(-20, result.chunkZ());
		assertEquals(TerrainDensityJob.MAX_SAMPLE_COUNT, result.densityCount());
		assertEquals(-0.25, result.densityResult().densityAt(123));
		assertEquals(0, fixture.authority.pendingTranscriptEntries());

		SeededLeafDensityResultGate.GateResult duplicate = SeededLeafDensityResultGate.accept(
			fixture.authority, OWNER, envelope
		);
		assertEquals(SeededLeafDensityResultGate.Status.CLAIM_REJECTED, duplicate.status());
		assertEquals(SeededLeafJobAuthority.ClaimStatus.DUPLICATE, duplicate.claimStatus());
	}

	@Test
	void rejectsWrongOwnerBeforeAttemptingMalformedDecompression() {
		Fixture fixture = fixture();
		SeededLeafDensityResultEnvelope malformed = new SeededLeafDensityResultEnvelope(
			fixture.claim,
			TerrainDensityJob.MAX_SAMPLE_COUNT,
			SeededLeafDensityResultEnvelope.Encoding.DEFLATE,
			new byte[] {1},
			0L,
			0L
		);

		SeededLeafDensityResultGate.GateResult wrongOwner = SeededLeafDensityResultGate.accept(
			fixture.authority, OTHER_OWNER, malformed
		);
		assertEquals(SeededLeafDensityResultGate.Status.CLAIM_REJECTED, wrongOwner.status());
		assertEquals(SeededLeafJobAuthority.ClaimStatus.OWNER_MISMATCH, wrongOwner.claimStatus());
		assertEquals(1, fixture.authority.pendingCount());

		SeededLeafDensityResultGate.GateResult invalid = SeededLeafDensityResultGate.accept(
			fixture.authority, OWNER, malformed
		);
		assertEquals(SeededLeafDensityResultGate.Status.INVALID_DENSITY_DATA, invalid.status());
		assertEquals(0, fixture.authority.pendingCount());
		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.DUPLICATE,
			SeededLeafDensityResultGate.accept(fixture.authority, OWNER, malformed).claimStatus()
		);
	}

	@Test
	void shapeAndDecodedValueFailuresConsumeTheOneShotClaim() {
		Fixture shapeFixture = fixture();
		SeededLeafDensityResultEnvelope wrongShape = new SeededLeafDensityResultEnvelope(
			shapeFixture.claim,
			1,
			SeededLeafDensityResultEnvelope.Encoding.RAW,
			new byte[Double.BYTES],
			0L,
			0L
		);
		assertEquals(
			SeededLeafDensityResultGate.Status.SHAPE_MISMATCH,
			SeededLeafDensityResultGate.accept(shapeFixture.authority, OWNER, wrongShape).status()
		);
		assertEquals(
			SeededLeafJobAuthority.ClaimStatus.DUPLICATE,
			SeededLeafDensityResultGate.accept(shapeFixture.authority, OWNER, wrongShape).claimStatus()
		);

		Fixture valueFixture = fixture();
		byte[] raw = new byte[TerrainDensityJob.MAX_SAMPLE_COUNT * Double.BYTES];
		ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putDouble(Double.NaN);
		SeededLeafDensityResultEnvelope invalidValue = new SeededLeafDensityResultEnvelope(
			valueFixture.claim,
			TerrainDensityJob.MAX_SAMPLE_COUNT,
			SeededLeafDensityResultEnvelope.Encoding.RAW,
			raw,
			0L,
			0L
		);
		assertEquals(
			SeededLeafDensityResultGate.Status.INVALID_DENSITY_DATA,
			SeededLeafDensityResultGate.accept(valueFixture.authority, OWNER, invalidValue).status()
		);
		assertTrue(valueFixture.authority.claim(OWNER, valueFixture.claim).authorization().isEmpty());
	}

	@Test
	void immutableBoundaryObjectsRejectMismatchedClaimAndDoNotExposeSeedBearingFields() {
		Fixture fixture = fixture();
		SeededLeafDensityResultEnvelope envelope = SeededLeafDensityResultEnvelope.encode(
			new SeededLeafDensityResult(fixture.claim, new double[TerrainDensityJob.MAX_SAMPLE_COUNT], 0L)
		);
		SeededLeafDensityResultGate.JobGeometry geometry = new SeededLeafDensityResultGate.JobGeometry(
			fixture.claim.jobId(), fixture.claim.contextId(), Identifier.parse("minecraft:overworld"), 10, -20,
			Identifier.parse("minecraft:overworld"), -64, 384, 4, 8
		);
		SeededLeafJobClaim mismatchedClaim = new SeededLeafJobClaim(
			fixture.claim.jobId(), OpaqueWorldgenContextId.fromHex("cd".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			fixture.claim.authenticationTag()
		);
		SeededLeafDensityResultEnvelope mismatchedEnvelope = new SeededLeafDensityResultEnvelope(
			mismatchedClaim, TerrainDensityJob.MAX_SAMPLE_COUNT, envelope.encoding(), envelope.encodedDensities(), 0L, 0L
		);
		assertThrows(IllegalArgumentException.class,
			() -> new SeededLeafDensityResultGate.ClaimedResult(geometry, mismatchedEnvelope, 1L));
		assertThrows(IllegalArgumentException.class,
			() -> new SeededLeafDensityResultGate.AcceptedResult(
				geometry.jobId(), geometry.contextId(), geometry.dimension(), geometry.chunkX(), geometry.chunkZ(),
				geometry.noiseSettings(), geometry.minY(), geometry.height(), geometry.cellWidth(), geometry.cellHeight(),
				new SeededLeafDensityResult(mismatchedClaim, new double[TerrainDensityJob.MAX_SAMPLE_COUNT], 0L)
			));

		for (Class<?> type : new Class<?>[] {
			SeededLeafDensityResultGate.JobGeometry.class,
			SeededLeafDensityResultGate.ClaimedResult.class,
			SeededLeafDensityResultGate.AcceptedResult.class
		}) {
			assertFalse(Stream.of(type.getRecordComponents()).map(component -> component.getName().toLowerCase())
				.anyMatch(name -> name.contains("transcript") || name.contains("seed") || name.contains("fingerprint")
					|| name.contains("authenticator") || name.contains("key")), type.getName());
		}
	}

	private static Fixture fixture() {
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
		return new Fixture(authority, authorization, SeededLeafJobClaim.fromAuthorization(authorization));
	}

	private static SeededLeafJobSpec spec() {
		return new SeededLeafJobSpec(
			Identifier.parse("minecraft:overworld"),
			10,
			-20,
			Identifier.parse("minecraft:overworld"),
			-64,
			384,
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

	private record Fixture(
		SeededLeafJobAuthority authority,
		AuthorizedSeededLeafJob authorization,
		SeededLeafJobClaim claim
	) {
	}
}
