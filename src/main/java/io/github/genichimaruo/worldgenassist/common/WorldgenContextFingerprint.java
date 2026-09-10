package io.github.genichimaruo.worldgenassist.common;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public final class WorldgenContextFingerprint {
	public static final String ALGORITHM = "SHA-256";
	public static final int BYTE_LENGTH = 32;
	public static final int HEX_LENGTH = BYTE_LENGTH * 2;

	private static final HexFormat HEX_FORMAT = HexFormat.of();

	private final byte[] digest;

	private WorldgenContextFingerprint(byte[] digest) {
		this.digest = digest;
	}

	public static WorldgenContextFingerprint fromBytes(byte[] digest) {
		Objects.requireNonNull(digest, "digest");
		if (digest.length != BYTE_LENGTH) {
			throw new IllegalArgumentException(
				"Worldgen context fingerprint must contain exactly " + BYTE_LENGTH + " bytes: " + digest.length
			);
		}
		return new WorldgenContextFingerprint(digest.clone());
	}

	public static WorldgenContextFingerprint fromHex(String digest) {
		Objects.requireNonNull(digest, "digest");
		if (digest.length() != HEX_LENGTH) {
			throw new IllegalArgumentException(
				"Worldgen context fingerprint must contain exactly " + HEX_LENGTH + " hexadecimal characters: " + digest.length()
			);
		}
		try {
			return fromBytes(HEX_FORMAT.parseHex(digest));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Worldgen context fingerprint contains a non-hexadecimal character", exception);
		}
	}

	public byte[] bytes() {
		return digest.clone();
	}

	public String toHex() {
		return HEX_FORMAT.formatHex(digest);
	}

	@Override
	public boolean equals(Object other) {
		return this == other
			|| other instanceof WorldgenContextFingerprint fingerprint
				&& MessageDigest.isEqual(digest, fingerprint.digest);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(digest);
	}

	@Override
	public String toString() {
		return toHex();
	}
}
