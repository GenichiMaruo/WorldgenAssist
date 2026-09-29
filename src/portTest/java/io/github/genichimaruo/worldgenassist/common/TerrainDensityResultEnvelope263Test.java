package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class TerrainDensityResultEnvelope263Test {
	private static final TerrainJobIdentity IDENTITY = new TerrainJobIdentity(
		WorldgenProtocolVersion.CURRENT, UUID.fromString("d39ecf82-123f-4e61-8c34-91368331336e"),
		Identifier.parse("example:skylands"), 4, -7,
		WorldgenContextFingerprint.fromBytes(new byte[WorldgenContextFingerprint.BYTE_LENGTH])
	);

	@Test
	void transportsExactFloatBitsAndUsesFourBytesPerSample() {
		double[] samples = {0.0, -0.0, 1.25, -1234.5, Float.MIN_VALUE, 1_000_000.0};
		TerrainDensityResultEnvelope envelope = TerrainDensityResultEnvelope.encode(
			new TerrainDensityResult(IDENTITY, samples, 10)
		);
		assertEquals(Float.BYTES * samples.length, envelope.rawDensityBytes());
		TerrainDensityResult decoded = envelope.decode();
		for (int index = 0; index < samples.length; index++) {
			assertEquals(Double.doubleToRawLongBits(samples[index]),
				Double.doubleToRawLongBits(decoded.densityAt(index)), "sample " + index);
		}
	}

	@Test
	void refusesAValueThatWouldChangeWhenConvertedToFloat() {
		assertThrows(IllegalArgumentException.class, () -> TerrainDensityResultEnvelope.encode(
			new TerrainDensityResult(IDENTITY, new double[] {1.0 + Math.ulp(1.0)}, 10)
		));
	}
}
