package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.github.genichimaruo.worldgenassist.common.*;
import net.minecraft.resources.Identifier;

class CompleteTerrain263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static final TerrainJobIdentity ID = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,
		UUID.fromString("09b10cc6-af88-4d52-9478-a67d4cc25313"),
		Identifier.withDefaultNamespace("overworld"), 1, -2, WorldgenContextFingerprint.fromBytes(new byte[32]));
	private static CompleteTerrainData sample() {
		byte[] codes = new byte[4096];
		short[][] offsets = {new short[]{12, 1, 12}};
		return new CompleteTerrainData(-64, 16, codes, new short[256], new short[256], offsets, new byte[32]);
	}
	@Test void immutableCompleteTransportPreservesExactOrderedOffsetsAndRejectsMixedKinds() {
		CompleteTerrainData data = sample();
		short[] offsets = data.postProcessing(0); offsets[0] = 0;
		assertArrayEquals(new short[]{12, 1, 12}, data.postProcessing(0));
		byte[] encoded = data.encode();
		assertEquals(data, CompleteTerrainData.decode(encoded, -64, 16));
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainData.decode(encoded, 0, 16));
		TerrainDensityResult result = TerrainDensityResult.fromCompleteTerrain(ID, data, 1);
		TerrainDensityResultEnvelope envelope = TerrainDensityResultEnvelope.encode(result, TerrainWorkKind.COMPLETE_TERRAIN);
		assertTrue(envelope.encoding().completeTerrain()); assertEquals(result, envelope.decode());
		assertThrows(IllegalStateException.class, result::densities);
		assertThrows(IllegalArgumentException.class, () -> TerrainDensityResultEnvelope.encode(result, TerrainWorkKind.DENSITY));
		assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class, () ->
			RemoteDensityValidator.Prepared.completeTerrain(new TerrainDensityJob(ID, 8675309, false,
				Identifier.withDefaultNamespace("overworld"), -64, 16, 1, 1, TerrainWorkKind.COMPLETE_TERRAIN), data, 0)
			.compare(TerrainDensityResult.fromFloats(ID, new float[4096], 0)));
	}
	@Test void rejectsMalformedDomainGeometryOffsetsAndCompressedBounds() {
		byte[] raw = sample().encode();
		byte[] bad = raw.clone(); bad[48] = (byte)255;
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainData.decode(bad, -64, 16));
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainData.decode(Arrays.copyOf(raw, raw.length - 1), -64, 16));
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainData.decode(Arrays.copyOf(raw, raw.length + 1), -64, 16));
		assertThrows(IllegalArgumentException.class, () -> new CompleteTerrainData(-64, 16, new byte[4096],
			new short[256], new short[256], new short[][]{new short[]{4096}}, new byte[32]));
		byte[] tooMany = raw.clone(); ByteBuffer.wrap(tooMany).putInt(48 + 4096 + 1024, Integer.MAX_VALUE);
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainData.decode(tooMany, -64, 16));
		var envelope = TerrainDensityResultEnvelope.encode(TerrainDensityResult.fromCompleteTerrain(ID, sample(), 0), TerrainWorkKind.COMPLETE_TERRAIN);
		byte[] wrongLength = envelope.encodedDensities(); ByteBuffer.wrap(wrongLength).putInt(Integer.MAX_VALUE);
		assertThrows(IllegalArgumentException.class, () -> new TerrainDensityResultEnvelope(ID, 4096, envelope.encoding(), wrongLength, 0, 0));
		byte[] trailing = Arrays.copyOf(envelope.encodedDensities(), envelope.encodedDensityBytes() + 1);
		assertThrows(IllegalArgumentException.class, () -> new TerrainDensityResultEnvelope(ID, 4096, envelope.encoding(), trailing, 0, 0).decode());
		assertThrows(IllegalArgumentException.class, () -> TerrainDensityResultEnvelope.rawBytes(4097, TerrainDensityResultEnvelope.Encoding.COMPLETE_TERRAIN));
	}
	@Test void twoSuccessfulInitialAuditsGateFurtherWorkAndPrivateDraws() {
		var policy = new CompleteTerrainAuditPolicy<String>(4, () -> 7);
		var first = policy.select("owner/context", UUID.randomUUID());
		var second = policy.select("owner/context", UUID.randomUUID());
		assertTrue(first.requiresFullAudit()); assertTrue(second.requiresFullAudit());
		assertFalse(policy.canAdmit("owner/context")); assertTrue(policy.accept(first));
		assertFalse(policy.canAdmit("owner/context")); assertFalse(policy.accept(first));
		assertTrue(policy.accept(second)); assertTrue(policy.canAdmit("owner/context"));
		assertFalse(policy.select("owner/context", UUID.randomUUID()).requiresFullAudit());
		var selected = new CompleteTerrainAuditPolicy<String>(4, () -> 0);
		assertTrue(selected.accept(selected.select("x", UUID.randomUUID())));
		assertTrue(selected.accept(selected.select("x", UUID.randomUUID())));
		assertTrue(selected.select("x", UUID.randomUUID()).requiresFullAudit());
	}
	@Test void cancellationEpochInvalidationAndAdmissionNeverAcceptStaleAudits() {
		var policy = new CompleteTerrainAuditPolicy<String>(2, () -> 7);
		UUID id = UUID.randomUUID(); var old = policy.select("owner/old", id);
		policy.invalidateMatching(key -> key.startsWith("owner/"));
		assertFalse(policy.accept(old));
		var replacement = policy.select("owner/new", id); policy.cancel(old);
		assertTrue(policy.current(replacement)); policy.cancel(replacement); assertEquals(0, policy.pendingCount());
		assertTrue(policy.select("owner/new", UUID.randomUUID()).requiresFullAudit());
		policy.select("other", UUID.randomUUID());
		assertThrows(IllegalStateException.class, () -> policy.select("third", UUID.randomUUID()));
		policy.clear(); assertEquals(0, policy.pendingCount()); assertFalse(policy.current(replacement));
	}
}
