package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeededLeafJobTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void exposesOnlyOpaqueSeedContextAndBoundedGeometry() {
		SeededLeafJob job = job();

		assertEquals(98_304, job.sampleCount());
		assertEquals(job, new SeededLeafJob(job.jobId(), job.contextId(), job.spec()));
		assertEquals(
			new SeededLeafJobClaim(job.jobId(), job.contextId(), SeededLeafJobAuthenticationTag.fromHex("cd".repeat(32))),
			job.claim(SeededLeafJobAuthenticationTag.fromHex("cd".repeat(32)))
		);
		assertFalse(Arrays.stream(SeededLeafJob.class.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName)
			.anyMatch(name -> name.equals("worldSeed") || name.equals("contextFingerprint")));
	}

	@Test
	void rejectsUnsupportedContextAndInvalidGeometry() {
		assertThrows(IllegalArgumentException.class, () -> copy(
			Identifier.parse("minecraft:the_nether"),
			Identifier.parse("minecraft:overworld"),
			-64,
			384,
			4,
			8
		));
		assertThrows(IllegalArgumentException.class, () -> copy(
			Identifier.parse("minecraft:overworld"),
			Identifier.parse("minecraft:amplified"),
			-64,
			384,
			4,
			8
		));
		assertThrows(IllegalArgumentException.class, () -> copy(
			Identifier.parse("minecraft:overworld"),
			Identifier.parse("minecraft:overworld"),
			-64,
			385,
			4,
			8
		));
		assertThrows(IllegalArgumentException.class, () -> copy(
			Identifier.parse("minecraft:overworld"),
			Identifier.parse("minecraft:overworld"),
			-63,
			384,
			4,
			8
		));
	}

	static SeededLeafJob job() {
		return copy(
			Identifier.parse("minecraft:overworld"),
			Identifier.parse("minecraft:overworld"),
			-64,
			384,
			4,
			8
		);
	}

	private static SeededLeafJob copy(
		Identifier dimension,
		Identifier noiseSettings,
		int minY,
		int height,
		int cellWidth,
		int cellHeight
	) {
		return new SeededLeafJob(
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			dimension,
			10,
			-20,
			noiseSettings,
			minY,
			height,
			cellWidth,
			cellHeight,
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
}
