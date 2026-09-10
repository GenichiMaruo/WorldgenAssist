package io.github.genichimaruo.worldgenassist.common;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable HMAC-SHA-256 tag for a seeded-leaf job. */
public final class SeededLeafJobAuthenticationTag {
	public static final int BYTE_LENGTH = 32;
	public static final int HEX_LENGTH = BYTE_LENGTH * 2;

	private static final HexFormat HEX_FORMAT = HexFormat.of();

	private final byte[] bytes;

	private SeededLeafJobAuthenticationTag(byte[] bytes) {
		this.bytes = bytes;
	}

	public static SeededLeafJobAuthenticationTag fromBytes(byte[] bytes) {
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length != BYTE_LENGTH) {
			throw new IllegalArgumentException(
				"Seeded-leaf authentication tag must contain exactly " + BYTE_LENGTH + " bytes: " + bytes.length
			);
		}
		return new SeededLeafJobAuthenticationTag(bytes.clone());
	}

	public static SeededLeafJobAuthenticationTag fromHex(String value) {
		Objects.requireNonNull(value, "value");
		if (value.length() != HEX_LENGTH) {
			throw new IllegalArgumentException(
				"Seeded-leaf authentication tag must contain exactly " + HEX_LENGTH + " hexadecimal characters: " + value.length()
			);
		}
		try {
			return fromBytes(HEX_FORMAT.parseHex(value));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Seeded-leaf authentication tag contains a non-hexadecimal character", exception);
		}
	}

	public byte[] bytes() {
		return bytes.clone();
	}

	public String toHex() {
		return HEX_FORMAT.formatHex(bytes);
	}

	@Override
	public boolean equals(Object other) {
		return this == other
			|| other instanceof SeededLeafJobAuthenticationTag tag
				&& MessageDigest.isEqual(bytes, tag.bytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bytes);
	}

	@Override
	public String toString() {
		return toHex();
	}
}
