package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeededLeafJobAuthenticatorTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void authenticatesEverySemanticJobFieldAndRejectsTampering() {
		SeededLeafJobAuthenticator authenticator = SeededLeafJobAuthenticator.fromKey(filled((byte)0x11));
		SeededLeafJob job = job();
		AuthorizedSeededLeafJob authorization = authenticator.authorize(job);

		assertTrue(authenticator.verify(authorization));
		List<SeededLeafJob> tamperedJobs = List.of(
			copy(job, new UUID(job.jobId().getMostSignificantBits(), job.jobId().getLeastSignificantBits() + 1), job.contextId(), job.chunkX(), job.chunkZ(), job.minY(), job.height(), job.cellWidth(), job.cellHeight(), job.transcript()),
			copy(job, job.jobId(), OpaqueWorldgenContextId.fromHex("bc".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)), job.chunkX(), job.chunkZ(), job.minY(), job.height(), job.cellWidth(), job.cellHeight(), job.transcript()),
			copy(job, job.jobId(), job.contextId(), job.chunkX() + 1, job.chunkZ(), job.minY(), job.height(), job.cellWidth(), job.cellHeight(), job.transcript()),
			copy(job, job.jobId(), job.contextId(), job.chunkX(), job.chunkZ() - 1, job.minY(), job.height(), job.cellWidth(), job.cellHeight(), job.transcript()),
			copy(job, job.jobId(), job.contextId(), job.chunkX(), job.chunkZ(), -56, 376, job.cellWidth(), job.cellHeight(), job.transcript()),
			copy(job, job.jobId(), job.contextId(), job.chunkX(), job.chunkZ(), job.minY(), job.height(), 8, 4, job.transcript()),
			copy(job, job.jobId(), job.contextId(), job.chunkX(), job.chunkZ(), job.minY(), job.height(), job.cellWidth(), job.cellHeight(), changedTranscript(job, false)),
			copy(job, job.jobId(), job.contextId(), job.chunkX(), job.chunkZ(), job.minY(), job.height(), job.cellWidth(), job.cellHeight(), changedTranscript(job, true))
		);
		for (SeededLeafJob tampered : tamperedJobs) {
			assertFalse(authenticator.verify(new AuthorizedSeededLeafJob(tampered, authorization.authenticationTag())));
		}

		SeededLeafJobAuthenticator otherKey = SeededLeafJobAuthenticator.fromKey(filled((byte)0x22));
		assertFalse(otherKey.verify(authorization));
	}

	@Test
	void keyMaterialIsBoundedDefensivelyCopiedAndRandomized() {
		byte[] key = filled((byte)0x33);
		SeededLeafJobAuthenticator authenticator = SeededLeafJobAuthenticator.fromKey(key);
		String beforeMutation = authenticator.authenticate(job()).toHex();
		Arrays.fill(key, (byte)0);
		assertTrue(authenticator.verify(authenticator.authorize(job())));
		assertEquals(beforeMutation, authenticator.authenticate(job()).toHex());

		assertThrows(IllegalArgumentException.class, () -> SeededLeafJobAuthenticator.fromKey(new byte[32]));
		assertThrows(IllegalArgumentException.class, () -> SeededLeafJobAuthenticator.fromKey(new byte[31]));

		SeededLeafJobAuthenticator first = SeededLeafJobAuthenticator.random(new SecureRandom());
		SeededLeafJobAuthenticator second = SeededLeafJobAuthenticator.random(new SecureRandom());
		assertNotEquals(first.authenticate(job()), second.authenticate(job()));
	}

	private static byte[] filled(byte value) {
		byte[] bytes = new byte[SeededLeafJobAuthenticator.KEY_BYTES];
		Arrays.fill(bytes, value);
		return bytes;
	}

	private static SeededLeafJob job() {
		return new SeededLeafJob(
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			OpaqueWorldgenContextId.fromHex("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH)),
			Identifier.parse("minecraft:overworld"),
			10,
			-20,
			Identifier.parse("minecraft:overworld"),
			-64,
			384,
			4,
			8,
			new SeededLeafTranscript(List.of(
				new SeededLeafTranscript.Entry(
					SeededLeafTranscript.Kind.NORMAL_NOISE,
					"normal:minecraft:temperature",
					1L,
					2L,
					3L,
					4L
				),
				new SeededLeafTranscript.Entry(
					SeededLeafTranscript.Kind.BLENDED_NOISE,
					"blended:0",
					5L,
					6L,
					7L,
					8L
				)
			))
		);
	}

	private static SeededLeafTranscript changedTranscript(SeededLeafJob job, boolean output) {
		ArrayList<SeededLeafTranscript.Entry> entries = new ArrayList<>(job.transcript().entries());
		SeededLeafTranscript.Entry first = entries.getFirst();
		entries.set(0, new SeededLeafTranscript.Entry(
			first.kind(),
			first.leafId(),
			output ? first.xBits() : first.xBits() ^ 1L,
			first.yBits(),
			first.zBits(),
			output ? first.valueBits() ^ 1L : first.valueBits()
		));
		return new SeededLeafTranscript(entries);
	}

	private static SeededLeafJob copy(
		SeededLeafJob job,
		UUID jobId,
		OpaqueWorldgenContextId contextId,
		int chunkX,
		int chunkZ,
		int minY,
		int height,
		int cellWidth,
		int cellHeight,
		SeededLeafTranscript transcript
	) {
		return new SeededLeafJob(
			jobId,
			contextId,
			job.dimension(),
			chunkX,
			chunkZ,
			job.noiseSettings(),
			minY,
			height,
			cellWidth,
			cellHeight,
			transcript
		);
	}
}
