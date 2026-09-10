package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SeededLeafFixtureConfigTest {
	@Test
	void bothFlagsAreExplicitAndPrivateWorldsAlwaysFailClosed() {
		String server = System.getProperty(SeededLeafFixtureConfig.SERVER_PROPERTY);
		String client = System.getProperty(SeededLeafFixtureConfig.CLIENT_PROPERTY);
		try {
			System.setProperty(SeededLeafFixtureConfig.SERVER_PROPERTY, "false");
			System.setProperty(SeededLeafFixtureConfig.CLIENT_PROPERTY, "false");
			assertFalse(SeededLeafFixtureConfig.payloadsEnabled());
			assertFalse(SeededLeafFixtureConfig.permitsWorld(8675309L));
			System.setProperty(SeededLeafFixtureConfig.CLIENT_PROPERTY, "true");
			assertTrue(SeededLeafFixtureConfig.payloadsEnabled());
			assertFalse(SeededLeafFixtureConfig.permitsWorld(8675309L));
			System.setProperty(SeededLeafFixtureConfig.SERVER_PROPERTY, "true");
			assertTrue(SeededLeafFixtureConfig.permitsWorld(8675309L));
			for (long seed : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 8675308L, 8675310L}) {
				assertFalse(SeededLeafFixtureConfig.permitsWorld(seed));
			}
			System.setProperty(SeededLeafFixtureConfig.SERVER_PROPERTY, "yes");
			assertThrows(IllegalArgumentException.class, SeededLeafFixtureConfig::serverEnabled);
			System.setProperty(SeededLeafFixtureConfig.CLIENT_PROPERTY, "1");
			assertThrows(IllegalArgumentException.class, SeededLeafFixtureConfig::clientEnabled);
		} finally {
			restore(SeededLeafFixtureConfig.SERVER_PROPERTY, server);
			restore(SeededLeafFixtureConfig.CLIENT_PROPERTY, client);
		}
	}
	private static void restore(String key, String value) {
		if (value == null) { System.clearProperty(key); } else { System.setProperty(key, value); }
	}
}
