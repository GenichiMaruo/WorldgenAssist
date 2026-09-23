package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFileTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void writesAndReadsPropertiesWithinTheBound() throws IOException {
		Path path = temporaryDirectory.resolve("nested/settings.properties");
		Properties expected = new Properties();
		expected.setProperty("enabled", "true");
		expected.setProperty("label", "trusted friends");

		SettingsFile.write(path, expected);

		assertEquals(expected, SettingsFile.read(path));
	}

	@Test
	void rejectsOversizedReadsBeforePropertiesParsing() throws IOException {
		Path path = temporaryDirectory.resolve("oversized.properties");
		Files.writeString(path, "x".repeat(16_385), StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> SettingsFile.read(path));
	}

	@Test
	void rejectsTruncatedPropertiesInput() throws IOException {
		Path path = temporaryDirectory.resolve("truncated.properties");
		Files.writeString(path, "enabled=\\u00", StandardCharsets.UTF_8);

		assertThrows(IllegalArgumentException.class, () -> SettingsFile.read(path));
	}

	@Test
	void rejectedOversizedWritePreservesThePreviousSettingsFile() throws IOException {
		Path path = temporaryDirectory.resolve("settings.properties");
		Properties previous = new Properties();
		previous.setProperty("enabled", "false");
		SettingsFile.write(path, previous);

		Properties oversized = new Properties();
		oversized.setProperty("value", "x".repeat(16_385));
		assertThrows(IOException.class, () -> SettingsFile.write(path, oversized));

		assertFalse(Files.notExists(path));
		assertEquals(previous, SettingsFile.read(path));
	}
}
