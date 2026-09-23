package io.github.genichimaruo.worldgenassist.client;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.SettingsFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

final class ClientSettings {
	private ClientSettings() {}
	private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("worldgen-assist-client.properties"); }
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
	static void save(boolean enabled) throws IOException {
		Properties p = new Properties(); p.setProperty("participation", Boolean.toString(enabled)); SettingsFile.write(path(), p);
	}
}
