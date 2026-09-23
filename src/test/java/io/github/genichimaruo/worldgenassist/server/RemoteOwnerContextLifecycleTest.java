package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RemoteOwnerContextLifecycleTest {
	@Test
	@SuppressWarnings("unchecked")
	void dimensionChangeRotatesEpochWithoutRevivingDisconnectedOwner() throws Exception {
		var constructor = RemoteWorldgenManager.class.getDeclaredConstructor(RemoteWorldgenConfig.class);
		constructor.setAccessible(true);
		var manager = constructor.newInstance(RemoteWorldgenConfig.defaults());
		var field = RemoteWorldgenManager.class.getDeclaredField("ownerGenerations");
		field.setAccessible(true);
		var epochs = (Map<UUID, Long>) field.get(manager);
		var counter = RemoteWorldgenManager.class.getDeclaredField("nextOwnerGeneration");
		counter.setAccessible(true);
		((AtomicLong) counter.get(manager)).set(2);
		UUID owner = new UUID(0, 1), other = new UUID(0, 2);
		epochs.put(owner, 1L);
		epochs.put(other, 2L);
		var invalidate = RemoteWorldgenManager.class.getDeclaredMethod("invalidateOwner", UUID.class, boolean.class);
		invalidate.setAccessible(true);
		invalidate.invoke(manager, owner, true);
		long destination = epochs.get(owner);
		assertTrue(destination > 2, "new results must receive a valid fresh epoch after moving");
		assertEquals(2L, epochs.get(other));
		invalidate.invoke(manager, owner, true);
		assertTrue(epochs.get(owner) > destination, "returning to a dimension must not reuse an old epoch");
		invalidate.invoke(manager, owner, false);
		assertFalse(epochs.containsKey(owner));
		invalidate.invoke(manager, owner, true);
		assertFalse(epochs.containsKey(owner), "a dimension event must not resurrect an unregistered owner");
	}
}
