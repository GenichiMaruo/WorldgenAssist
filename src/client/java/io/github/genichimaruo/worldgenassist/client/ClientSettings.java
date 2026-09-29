package io.github.genichimaruo.worldgenassist.client;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.WorldgenPlatform;
import io.github.genichimaruo.worldgenassist.common.SettingsFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

final class ClientSettings {
	private ClientSettings() {}
	private static Path path() { return WorldgenPlatform.configDir().resolve("worldgen-assist-client.properties"); }
	static boolean participation() {
		try {
			String value = SettingsFile.read(path()).getProperty("participation", "true");
			if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("Invalid participation setting");
			return Boolean.parseBoolean(value);
		} catch (IOException | IllegalArgumentException exception) {
			WorldgenAssist.LOGGER.warn("[CAWG] Invalid client settings; participation disabled", exception);
			return false;
		}
	}
	static int workerThreads() {
		String override = System.getProperty("worldgen_assist.client.worker_threads");
		try {
			String value = override == null ? SettingsFile.read(path()).getProperty("worker_threads", "2") : override;
			int threads = Integer.parseInt(value.trim());
			if (threads < 1 || threads > 4) throw new IllegalArgumentException("worker_threads must be 1..4");
			return threads;
		} catch (IOException | IllegalArgumentException exception) {
			WorldgenAssist.LOGGER.warn("[CAWG] Invalid worker_threads; using one client worker", exception);
			return 1;
		}
	}
	static void save(boolean enabled) throws IOException {
		Properties p = SettingsFile.read(path());
		p.setProperty("participation", Boolean.toString(enabled));
		SettingsFile.write(path(), p);
	}
}
