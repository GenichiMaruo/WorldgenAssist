package io.github.genichimaruo.worldgenassist.common;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Small, bounded settings file. Failed writes never replace the previous file. */
public final class SettingsFile {
	private static final int MAX_BYTES = 16_384;
	private SettingsFile() {}
	public static Properties read(Path path) throws IOException {
		Properties values = new Properties();
		if (!Files.exists(path)) return values;
		try (var input = Files.newInputStream(path)) {
			byte[] bytes = input.readNBytes(MAX_BYTES + 1);
			if (bytes.length > MAX_BYTES) throw new IOException("Settings file exceeds size limit");
			values.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
		}
		return values;
	}
	public static synchronized void write(Path path, Properties values) throws IOException {
		StringWriter writer = new StringWriter();
		values.store(writer, "WorldgenAssist settings");
		byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_BYTES) throw new IOException("Settings file exceeds size limit");
		Files.createDirectories(path.toAbsolutePath().getParent());
		Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "worldgen-assist-", ".tmp");
		try {
			Files.write(temporary, bytes);
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
