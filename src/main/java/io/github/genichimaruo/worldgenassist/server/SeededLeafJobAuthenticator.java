package io.github.genichimaruo.worldgenassist.server;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;

/** Server-only HMAC authority for the unregistered seeded-leaf job draft. */
public final class SeededLeafJobAuthenticator {
	public static final int KEY_BYTES = 32;

	private static final String ALGORITHM = "HmacSHA256";
	private static final byte[] DOMAIN = "worldgen_assist/seeded_leaf_job_auth/v1"
		.getBytes(StandardCharsets.UTF_8);

	private final SecretKeySpec key;

	private SeededLeafJobAuthenticator(byte[] keyBytes) {
		this.key = new SecretKeySpec(keyBytes, ALGORITHM);
	}

	public static SeededLeafJobAuthenticator random(SecureRandom random) {
		Objects.requireNonNull(random, "random");
		byte[] keyBytes = new byte[KEY_BYTES];
		do {
			random.nextBytes(keyBytes);
		} while (isAllZero(keyBytes));
		return new SeededLeafJobAuthenticator(keyBytes);
	}

	public static SeededLeafJobAuthenticator fromKey(byte[] keyBytes) {
		Objects.requireNonNull(keyBytes, "keyBytes");
		if (keyBytes.length != KEY_BYTES) {
			throw new IllegalArgumentException("Seeded-leaf authentication key must contain exactly " + KEY_BYTES + " bytes");
		}
		if (isAllZero(keyBytes)) {
			throw new IllegalArgumentException("Seeded-leaf authentication key cannot be all zero");
		}
		return new SeededLeafJobAuthenticator(keyBytes.clone());
	}

	public AuthorizedSeededLeafJob authorize(SeededLeafJob job) {
		return new AuthorizedSeededLeafJob(job, authenticate(job));
	}

	public boolean verify(AuthorizedSeededLeafJob authorization) {
		Objects.requireNonNull(authorization, "authorization");
		return MessageDigest.isEqual(
			authorization.authenticationTag().bytes(),
			authenticate(authorization.job()).bytes()
		);
	}

	public SeededLeafJobAuthenticationTag authenticate(SeededLeafJob job) {
		Objects.requireNonNull(job, "job");
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			updateBytes(mac, DOMAIN);
			updateInt(mac, SeededLeafJob.FORMAT_VERSION);
			updateString(mac, SeededLeafJob.DENSITY_GRAPH_ID);
			updateLong(mac, job.jobId().getMostSignificantBits());
			updateLong(mac, job.jobId().getLeastSignificantBits());
			updateBytes(mac, job.contextId().bytes());
			updateString(mac, job.dimension().toString());
			updateInt(mac, job.chunkX());
			updateInt(mac, job.chunkZ());
			updateString(mac, job.noiseSettings().toString());
			updateInt(mac, job.minY());
			updateInt(mac, job.height());
			updateInt(mac, job.cellWidth());
			updateInt(mac, job.cellHeight());
			updateInt(mac, SeededLeafTranscript.FORMAT_VERSION);
			updateInt(mac, job.transcript().size());
			for (SeededLeafTranscript.Entry entry : job.transcript().entries()) {
				updateInt(mac, entry.kind().ordinal());
				updateString(mac, entry.leafId());
				updateLong(mac, entry.xBits());
				updateLong(mac, entry.yBits());
				updateLong(mac, entry.zBits());
				updateLong(mac, entry.valueBits());
			}
			return SeededLeafJobAuthenticationTag.fromBytes(mac.doFinal());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("JVM does not provide " + ALGORITHM, exception);
		}
	}

	private static void updateString(Mac mac, String value) {
		updateBytes(mac, value.getBytes(StandardCharsets.UTF_8));
	}

	private static void updateBytes(Mac mac, byte[] bytes) {
		updateInt(mac, bytes.length);
		mac.update(bytes);
	}

	private static void updateInt(Mac mac, int value) {
		mac.update((byte)(value >>> 24));
		mac.update((byte)(value >>> 16));
		mac.update((byte)(value >>> 8));
		mac.update((byte)value);
	}

	private static void updateLong(Mac mac, long value) {
		mac.update((byte)(value >>> 56));
		mac.update((byte)(value >>> 48));
		mac.update((byte)(value >>> 40));
		mac.update((byte)(value >>> 32));
		mac.update((byte)(value >>> 24));
		mac.update((byte)(value >>> 16));
		mac.update((byte)(value >>> 8));
		mac.update((byte)value);
	}

	private static boolean isAllZero(byte[] bytes) {
		int aggregate = 0;
		for (byte value : bytes) {
			aggregate |= value;
		}
		return aggregate == 0;
	}
}
