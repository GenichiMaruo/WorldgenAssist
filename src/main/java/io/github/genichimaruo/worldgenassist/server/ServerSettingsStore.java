package io.github.genichimaruo.worldgenassist.server;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.SettingsFile;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class ServerSettingsStore {
	private ServerSettingsStore() {}
	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("worldgen-assist-server.properties");
	}
	public static RemoteWorldgenConfig load() {
		try { return decode(SettingsFile.read(path())); }
		catch (IOException | IllegalArgumentException exception) {
			WorldgenAssist.LOGGER.warn("[CAWG] Invalid saved server settings; remote assistance disabled", exception);
			return RemoteWorldgenConfig.defaults();
		}
	}
	public static void save(RemoteWorldgenConfig config) throws IOException {
		SettingsFile.write(path(), encode(config));
	}
	public static Properties encode(RemoteWorldgenConfig c) {
		Properties p = new Properties();
		p.setProperty("enabled", Boolean.toString(c.enabled()));
		p.setProperty("seed_disclosure", c.seedDisclosureMode().name());
		p.setProperty("max_in_flight", Integer.toString(c.maxInFlightJobs()));
		p.setProperty("timeout_ms", Long.toString(c.jobTimeout().toMillis()));
		p.setProperty("cache_entries", Integer.toString(c.cacheEntries()));
		p.setProperty("prediction", Boolean.toString(c.predictionEnabled()));
		p.setProperty("prediction_interval_ticks", Integer.toString(c.predictionIntervalTicks()));
		p.setProperty("prediction_lead_chunks", Integer.toString(c.predictionLeadChunks()));
		p.setProperty("validation_cells", Integer.toString(c.validationSampleCells()));
		return p;
	}
	public static RemoteWorldgenConfig decode(Properties p) {
		Properties d = encode(RemoteWorldgenConfig.defaults());
		for (String key : p.stringPropertyNames()) {
			if (!d.containsKey(key)) throw new IllegalArgumentException("Unknown server setting: " + key);
			d.setProperty(key, p.getProperty(key));
		}
		return new RemoteWorldgenConfig(bool(d, "enabled"),
			RemoteWorldgenConfig.SeedDisclosureMode.valueOf(d.getProperty("seed_disclosure").toUpperCase(java.util.Locale.ROOT)),
			Integer.parseInt(d.getProperty("max_in_flight")), Duration.ofMillis(Long.parseLong(d.getProperty("timeout_ms"))),
			Integer.parseInt(d.getProperty("cache_entries")), bool(d, "prediction"),
			Integer.parseInt(d.getProperty("prediction_interval_ticks")), Integer.parseInt(d.getProperty("prediction_lead_chunks")),
			Integer.parseInt(d.getProperty("validation_cells")));
	}
	private static boolean bool(Properties p, String key) {
		String value = p.getProperty(key);
		if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("Invalid boolean: " + key);
		return Boolean.parseBoolean(value);
	}
}
