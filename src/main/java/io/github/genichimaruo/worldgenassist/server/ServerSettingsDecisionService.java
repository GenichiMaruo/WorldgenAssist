package io.github.genichimaruo.worldgenassist.server;

import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;

/** Server-thread decision and optimistic-revision state for settings requests. */
final class ServerSettingsDecisionService {
	@FunctionalInterface
	interface Loader {
		RemoteWorldgenConfig load();
	}

	@FunctionalInterface
	interface Saver {
		void save(RemoteWorldgenConfig config) throws IOException;
	}

	private final Loader loader;
	private final Saver saver;
	private int revision;

	ServerSettingsDecisionService(Loader loader, Saver saver) {
		this.loader = Objects.requireNonNull(loader, "loader");
		this.saver = Objects.requireNonNull(saver, "saver");
	}

	/** Returns null for invalid client directions, which the networking receiver must ignore. */
	SettingsPayload decide(PermissionSet permissions, SettingsPayload request) {
		if (request.action() != SettingsPayload.READ && request.action() != SettingsPayload.SAVE) return null;
		if (!mayManage(permissions)) {
			return new SettingsPayload(SettingsPayload.DENIED, 0, request.requestId(), RemoteWorldgenConfig.defaults());
		}
		if (request.action() == SettingsPayload.READ) {
			return new SettingsPayload(SettingsPayload.STATE, revision, request.requestId(), loader.load());
		}
		if (request.revision() != revision) {
			return new SettingsPayload(SettingsPayload.STALE, revision, request.requestId(), loader.load());
		}
		try {
			saver.save(request.config());
			revision++;
			return new SettingsPayload(SettingsPayload.SAVED, revision, request.requestId(), loader.load());
		} catch (IOException exception) {
			return new SettingsPayload(SettingsPayload.IO_ERROR, revision, request.requestId(), loader.load());
		}
	}

	void reset() {
		revision = 0;
	}

	static boolean mayManage(PermissionSet permissions) {
		return permissions.hasPermission(Permissions.COMMANDS_ADMIN);
	}
}
