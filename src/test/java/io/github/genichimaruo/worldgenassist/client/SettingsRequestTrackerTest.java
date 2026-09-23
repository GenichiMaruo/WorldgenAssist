package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettingsRequestTrackerTest {
	@Test
	void ignoresALateResponseAfterTimeoutAndANewerRequest() {
		SettingsRequestTracker tracker = new SettingsRequestTracker();
		long timedOutRequest = tracker.begin();
		tracker.clear();
		long currentRequest = tracker.begin();

		assertFalse(tracker.accepts(timedOutRequest));
		assertTrue(tracker.accepts(currentRequest));
	}

	@Test
	void assignsDifferentIdsToDifferentScreenInstances() {
		SettingsRequestTracker firstScreen = new SettingsRequestTracker();
		SettingsRequestTracker secondScreen = new SettingsRequestTracker();
		long firstRequest = firstScreen.begin();
		long secondRequest = secondScreen.begin();

		assertNotEquals(firstRequest, secondRequest);
		assertFalse(secondScreen.accepts(firstRequest));
		assertTrue(secondScreen.accepts(secondRequest));
	}
}
