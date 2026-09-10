package io.github.genichimaruo.worldgenassist.common;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** A server-random identifier with no seed-derived preimage. */
public final class OpaqueWorldgenContextId {
	public static final int BYTE_LENGTH = 32;
	public static final int HEX_LENGTH = BYTE_LENGTH * 2;

	private static final HexFormat HEX_FORMAT = HexFormat.of();

	private final byte[] bytes;

	private OpaqueWorldgenContextId(byte[] bytes) {
		this.bytes = bytes;
	}

	public static OpaqueWorldgenContextId random(SecureRandom random) {
		Objects.requireNonNull(random, "random");
		byte[] bytes = new byte[BYTE_LENGTH];
		do {
			random.nextBytes(bytes);
		} while (isAllZero(bytes));
		return new OpaqueWorldgenContextId(bytes);
	}

	public static OpaqueWorldgenContextId fromBytes(byte[] bytes) {
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length != BYTE_LENGTH) {
			throw new IllegalArgumentException("Opaque context ID must contain exactly " + BYTE_LENGTH + " bytes: " + bytes.length);
		}
		if (isAllZero(bytes)) {
			throw new IllegalArgumentException("Opaque context ID cannot be all zero");
		}
		return new OpaqueWorldgenContextId(bytes.clone());
	}

	public static OpaqueWorldgenContextId fromHex(String value) {
		Objects.requireNonNull(value, "value");
		if (value.length() != HEX_LENGTH) {
			throw new IllegalArgumentException("Opaque context ID must contain exactly " + HEX_LENGTH + " hexadecimal characters: " + value.length());
		}
		try {
			return fromBytes(HEX_FORMAT.parseHex(value));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Opaque context ID contains a non-hexadecimal character or is all zero", exception);
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
			|| other instanceof OpaqueWorldgenContextId contextId
				&& MessageDigest.isEqual(bytes, contextId.bytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bytes);
	}

	@Override
	public String toString() {
		return toHex();
	}

	private static boolean isAllZero(byte[] bytes) {
		int aggregate = 0;
		for (byte value : bytes) {
			aggregate |= value;
		}
		return aggregate == 0;
	}
}
