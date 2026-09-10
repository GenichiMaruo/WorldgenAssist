package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class WorldgenContextFingerprintTest {
	@Test
	void defensivelyCopiesInputAndOutputBytes() {
		byte[] source = new byte[WorldgenContextFingerprint.BYTE_LENGTH];
		source[0] = 42;
		WorldgenContextFingerprint fingerprint = WorldgenContextFingerprint.fromBytes(source);

		source[0] = 7;
		byte[] returned = fingerprint.bytes();
		returned[0] = 9;

		assertEquals(42, fingerprint.bytes()[0]);
	}

	@Test
	void parsesAndFormatsCanonicalSha256Hex() {
		byte[] bytes = new byte[WorldgenContextFingerprint.BYTE_LENGTH];
		Arrays.fill(bytes, (byte)0xab);
		String expected = "ab".repeat(WorldgenContextFingerprint.BYTE_LENGTH);

		WorldgenContextFingerprint fingerprint = WorldgenContextFingerprint.fromHex(expected.toUpperCase());

		assertEquals(expected, fingerprint.toHex());
		assertEquals(expected, fingerprint.toString());
		assertArrayEquals(bytes, fingerprint.bytes());
		assertEquals(fingerprint, WorldgenContextFingerprint.fromBytes(bytes));
		assertEquals(fingerprint.hashCode(), WorldgenContextFingerprint.fromBytes(bytes).hashCode());
	}

	@Test
	void distinguishesDifferentFingerprints() {
		byte[] first = new byte[WorldgenContextFingerprint.BYTE_LENGTH];
		byte[] second = new byte[WorldgenContextFingerprint.BYTE_LENGTH];
		second[WorldgenContextFingerprint.BYTE_LENGTH - 1] = 1;

		assertNotEquals(WorldgenContextFingerprint.fromBytes(first), WorldgenContextFingerprint.fromBytes(second));
	}

	@Test
	void rejectsWrongLengthsAndInvalidHex() {
		assertThrows(NullPointerException.class, () -> WorldgenContextFingerprint.fromBytes(null));
		assertThrows(IllegalArgumentException.class, () -> WorldgenContextFingerprint.fromBytes(new byte[31]));
		assertThrows(IllegalArgumentException.class, () -> WorldgenContextFingerprint.fromBytes(new byte[33]));
		assertThrows(IllegalArgumentException.class, () -> WorldgenContextFingerprint.fromHex("00".repeat(31)));
		assertThrows(IllegalArgumentException.class, () -> WorldgenContextFingerprint.fromHex("gg".repeat(32)));
	}
}
