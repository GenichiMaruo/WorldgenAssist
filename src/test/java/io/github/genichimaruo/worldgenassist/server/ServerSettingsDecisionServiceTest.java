package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.Test;

class ServerSettingsDecisionServiceTest {
	@Test
	void deniedReadsAndSavesDoNotAccessTheStore() {
		Store store = new Store(RemoteWorldgenConfig.defaults());
		ServerSettingsDecisionService decisions = store.decisions();

		SettingsPayload deniedRead = decisions.decide(PermissionSet.NO_PERMISSIONS, request(SettingsPayload.READ, 0, 7L, RemoteWorldgenConfig.defaults()));
		SettingsPayload deniedSave = decisions.decide(PermissionSet.NO_PERMISSIONS, request(SettingsPayload.SAVE, 0, 8L, enabledOnly()));

		assertEquals(SettingsPayload.DENIED, deniedRead.action());
		assertEquals(7L, deniedRead.requestId());
		assertEquals(SettingsPayload.DENIED, deniedSave.action());
		assertEquals(8L, deniedSave.requestId());
		assertEquals(0, store.loads.get());
		assertEquals(0, store.saves.get());
	}

	@Test
	void authorizedReadDoesNotMutateTheStore() {
		Store store = new Store(enabledOnly());

		SettingsPayload response = store.decisions().decide(PermissionSet.ALL_PERMISSIONS, request(SettingsPayload.READ, 0, 9L, RemoteWorldgenConfig.defaults()));

		assertEquals(SettingsPayload.STATE, response.action());
		assertEquals(0, response.revision());
		assertEquals(9L, response.requestId());
		assertEquals(enabledOnly(), response.config());
		assertEquals(1, store.loads.get());
		assertEquals(0, store.saves.get());
	}

	@Test
	void authorizedSaveEchoesIdIncrementsRevisionAndStaleSavePreservesFirstValue() {
		Store store = new Store(RemoteWorldgenConfig.defaults());
		ServerSettingsDecisionService decisions = store.decisions();
		RemoteWorldgenConfig first = enabledOnly();
		RemoteWorldgenConfig second = RemoteWorldgenConfig.defaults();

		SettingsPayload saved = decisions.decide(PermissionSet.ALL_PERMISSIONS, request(SettingsPayload.SAVE, 0, 10L, first));
		SettingsPayload stale = decisions.decide(PermissionSet.ALL_PERMISSIONS, request(SettingsPayload.SAVE, 0, 11L, second));

		assertEquals(SettingsPayload.SAVED, saved.action());
		assertEquals(1, saved.revision());
		assertEquals(10L, saved.requestId());
		assertEquals(first, store.value);
		assertEquals(SettingsPayload.STALE, stale.action());
		assertEquals(1, stale.revision());
		assertEquals(11L, stale.requestId());
		assertEquals(first, stale.config());
		assertEquals(1, store.saves.get());
	}

	@Test
	void ioFailurePreservesRevisionForARetry() {
		Store store = new Store(RemoteWorldgenConfig.defaults());
		store.failWrites = true;
		ServerSettingsDecisionService decisions = store.decisions();
		RemoteWorldgenConfig value = enabledOnly();

		SettingsPayload failed = decisions.decide(PermissionSet.ALL_PERMISSIONS, request(SettingsPayload.SAVE, 0, 12L, value));
		store.failWrites = false;
		SettingsPayload retried = decisions.decide(PermissionSet.ALL_PERMISSIONS, request(SettingsPayload.SAVE, 0, 13L, value));

		assertEquals(SettingsPayload.IO_ERROR, failed.action());
		assertEquals(0, failed.revision());
		assertEquals(12L, failed.requestId());
		assertEquals(SettingsPayload.SAVED, retried.action());
		assertEquals(1, retried.revision());
		assertEquals(13L, retried.requestId());
		assertEquals(value, store.value);
	}

	@Test
	void invalidClientActionsAreIgnoredWithoutStoreAccess() {
		Store store = new Store(RemoteWorldgenConfig.defaults());

		assertNull(store.decisions().decide(PermissionSet.ALL_PERMISSIONS, request(SettingsPayload.STATE, 0, 14L, RemoteWorldgenConfig.defaults())));
		assertEquals(0, store.loads.get());
		assertEquals(0, store.saves.get());
	}

	@Test
	void resetRestoresTheInitialRevision() {
		Store store = new Store(RemoteWorldgenConfig.defaults());
		ServerSettingsDecisionService decisions = store.decisions();
		decisions.decide(PermissionSet.ALL_PERMISSIONS, request(SettingsPayload.SAVE, 0, 15L, enabledOnly()));
		decisions.reset();

		SettingsPayload response = decisions.decide(PermissionSet.ALL_PERMISSIONS, request(SettingsPayload.READ, 0, 16L, RemoteWorldgenConfig.defaults()));

		assertEquals(0, response.revision());
		assertTrue(ServerSettingsDecisionService.mayManage(PermissionSet.ALL_PERMISSIONS));
		assertFalse(ServerSettingsDecisionService.mayManage(PermissionSet.NO_PERMISSIONS));
	}

	private static SettingsPayload request(int action, int revision, long requestId, RemoteWorldgenConfig config) {
		return new SettingsPayload(action, revision, requestId, config);
	}

	private static RemoteWorldgenConfig enabledOnly() {
		RemoteWorldgenConfig defaults = RemoteWorldgenConfig.defaults();
		return new RemoteWorldgenConfig(true, RemoteWorldgenConfig.SeedDisclosureMode.DENY, defaults.maxInFlightJobs(), defaults.jobTimeout(),
			defaults.cacheEntries(), defaults.predictionEnabled(), defaults.predictionIntervalTicks(), defaults.predictionLeadChunks(), defaults.validationSampleCells());
	}

	private static final class Store {
		private RemoteWorldgenConfig value;
		private boolean failWrites;
		private final AtomicInteger loads = new AtomicInteger();
		private final AtomicInteger saves = new AtomicInteger();

		private Store(RemoteWorldgenConfig value) {
			this.value = value;
		}

		private ServerSettingsDecisionService decisions() {
			return new ServerSettingsDecisionService(this::load, this::save);
		}

		private RemoteWorldgenConfig load() {
			loads.incrementAndGet();
			return value;
		}

		private void save(RemoteWorldgenConfig config) throws IOException {
			saves.incrementAndGet();
			if (failWrites) throw new IOException("expected test failure");
			value = config;
		}
	}
}
