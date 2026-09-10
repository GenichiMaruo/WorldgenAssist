package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SeededLeafJobAuthenticationTagTest {
	@Test
	void defensivelyCopiesAndFormatsCanonicalHex() {
		byte[] source = new byte[SeededLeafJobAuthenticationTag.BYTE_LENGTH];
		Arrays.fill(source, (byte)0xcd);
		SeededLeafJobAuthenticationTag tag = SeededLeafJobAuthenticationTag.fromBytes(source);
		source[0] = 0;
		byte[] exposed = tag.bytes();
		exposed[1] = 0;

		assertEquals("cd".repeat(SeededLeafJobAuthenticationTag.BYTE_LENGTH), tag.toHex());
		byte[] expected = new byte[SeededLeafJobAuthenticationTag.BYTE_LENGTH];
		Arrays.fill(expected, (byte)0xcd);
		assertArrayEquals(expected, tag.bytes());
		assertEquals(tag, SeededLeafJobAuthenticationTag.fromHex(tag.toHex().toUpperCase()));
		assertEquals(tag.hashCode(), SeededLeafJobAuthenticationTag.fromBytes(tag.bytes()).hashCode());
	}

	@Test
	void rejectsWrongLengthAndInvalidHex() {
		assertThrows(IllegalArgumentException.class, () -> SeededLeafJobAuthenticationTag.fromBytes(new byte[31]));
		assertThrows(IllegalArgumentException.class, () -> SeededLeafJobAuthenticationTag.fromHex("cd".repeat(31)));
		assertThrows(IllegalArgumentException.class, () -> SeededLeafJobAuthenticationTag.fromHex("gg".repeat(32)));
	}
}
