package io.github.genichimaruo.worldgenassist;

import java.nio.file.Path;
import java.util.Objects;

/** Small loader-owned bootstrap facts shared by server policy and client worker. */
public final class WorldgenPlatform {
	private static volatile Path configDir;
	private static volatile String version;
	private static volatile boolean development;

	private WorldgenPlatform() {}

	public static synchronized void install(Path directory, String modVersion, boolean isDevelopment) {
		Path normalized = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
		String checkedVersion = Objects.requireNonNull(modVersion, "modVersion");
		if (checkedVersion.isBlank()) throw new IllegalArgumentException("Blank mod version");
		if (configDir != null && (!configDir.equals(normalized) || !version.equals(checkedVersion))) {
			throw new IllegalStateException("Conflicting WorldgenAssist loader initialization");
		}
		configDir = normalized;
		version = checkedVersion;
		development = isDevelopment;
	}

	public static Path configDir() {
		Path value = configDir;
		if (value == null) throw new IllegalStateException("WorldgenAssist loader is not initialized");
		return value;
	}

	public static String version() {
		String value = version;
		if (value == null) throw new IllegalStateException("WorldgenAssist loader is not initialized");
		return value;
	}

	public static boolean isDevelopment() { return development; }
}
