package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class OpaqueWorldgenContextIdTest {
	@Test
	void defensivelyCopiesAndFormatsCanonicalHex() {
		byte[] source = new byte[OpaqueWorldgenContextId.BYTE_LENGTH];
		Arrays.fill(source, (byte)0xab);
		OpaqueWorldgenContextId contextId = OpaqueWorldgenContextId.fromBytes(source);
		source[0] = 0;
		byte[] exposed = contextId.bytes();
		exposed[1] = 0;

		assertEquals("ab".repeat(OpaqueWorldgenContextId.BYTE_LENGTH), contextId.toHex());
		assertArrayEquals(HexFormatHelper.abBytes(), contextId.bytes());
		assertEquals(contextId, OpaqueWorldgenContextId.fromHex(contextId.toHex().toUpperCase()));
		assertEquals(contextId.hashCode(), OpaqueWorldgenContextId.fromBytes(contextId.bytes()).hashCode());
	}

	@Test
	void rejectsZeroWrongLengthAndInvalidHex() {
		assertThrows(IllegalArgumentException.class, () -> OpaqueWorldgenContextId.fromBytes(new byte[32]));
		assertThrows(IllegalArgumentException.class, () -> OpaqueWorldgenContextId.fromBytes(new byte[31]));
		assertThrows(IllegalArgumentException.class, () -> OpaqueWorldgenContextId.fromHex("01".repeat(31)));
		assertThrows(IllegalArgumentException.class, () -> OpaqueWorldgenContextId.fromHex("gg".repeat(32)));
	}

	@Test
	void secureRandomFactoryProducesDistinctNonZeroIds() {
		SecureRandom random = new SecureRandom();
		OpaqueWorldgenContextId first = OpaqueWorldgenContextId.random(random);
		OpaqueWorldgenContextId second = OpaqueWorldgenContextId.random(random);

		assertNotEquals(first, second);
		assertNotEquals("00".repeat(OpaqueWorldgenContextId.BYTE_LENGTH), first.toHex());
		assertNotEquals("00".repeat(OpaqueWorldgenContextId.BYTE_LENGTH), second.toHex());
	}

	private static final class HexFormatHelper {
		private static byte[] abBytes() {
			byte[] bytes = new byte[OpaqueWorldgenContextId.BYTE_LENGTH];
			Arrays.fill(bytes, (byte)0xab);
			return bytes;
		}
	}
}
