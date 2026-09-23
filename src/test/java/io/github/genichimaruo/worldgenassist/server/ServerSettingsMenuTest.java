package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import java.util.UUID;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.Test;

class ServerSettingsMenuTest {
	@Test
	void rapidRequestGetsCorrelatedRetryResponseWithoutChangingPolicy() {
		UUID owner = UUID.randomUUID();
		RemoteWorldgenConfig defaults = RemoteWorldgenConfig.defaults();
		SettingsPayload first = new SettingsPayload(SettingsPayload.READ, 0, 10L, defaults);
		SettingsPayload second = new SettingsPayload(SettingsPayload.SAVE, 0, 11L, defaults);
		assertNull(ServerSettingsMenu.rateLimit(owner, 1_000_000_000L, first));
		SettingsPayload limited = ServerSettingsMenu.rateLimit(owner, 1_100_000_000L, second);
		assertEquals(SettingsPayload.RATE_LIMITED, limited.action());
		assertEquals(11L, limited.requestId());
		assertEquals(defaults, limited.config());
		assertNull(ServerSettingsMenu.rateLimit(owner, 1_250_000_000L, second));
	}
	@Test
	void onlyAnAuthoritativePermissionSetContainingAdminMayManagePolicy() {
		assertFalse(ServerSettingsMenu.mayManage(PermissionSet.NO_PERMISSIONS));
		assertTrue(ServerSettingsMenu.mayManage(PermissionSet.ALL_PERMISSIONS));
	}
}
