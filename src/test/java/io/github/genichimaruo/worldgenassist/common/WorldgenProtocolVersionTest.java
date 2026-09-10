package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldgenProtocolVersionTest {
	@Test
	void acceptsTheCurrentVersionAndRepresentsFutureVersionsForRejection() {
		assertTrue(WorldgenProtocolVersion.CURRENT.isSupported());
		assertDoesNotThrow(WorldgenProtocolVersion.CURRENT::requireSupported);

		WorldgenProtocolVersion future = new WorldgenProtocolVersion(3);
		assertFalse(future.isSupported());
		assertThrows(IllegalArgumentException.class, future::requireSupported);
	}

	@Test
	void rejectsValuesOutsideTheUnsignedShortEnvelope() {
		assertThrows(IllegalArgumentException.class, () -> new WorldgenProtocolVersion(0));
		assertThrows(IllegalArgumentException.class, () -> new WorldgenProtocolVersion(65_536));
	}
}
