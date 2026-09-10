package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import org.junit.jupiter.api.Test;

class SeededLeafProtocolGuardTest {
	private static final Pattern LOGGER_CALL = Pattern.compile(
		"(?:WorldgenAssist\\.)?LOGGER\\.(?:trace|debug|info|warn|error)\\((?s:.*?)\\);"
	);
	private static final Pattern SENSITIVE_LOG_VALUE = Pattern.compile(
		"(?i)(?:worldSeed\\s*\\(|getSeed\\s*\\(|\\.seed\\s*\\(|\\.transcript\\s*\\(|"
			+ "\\.contextId\\s*\\(|\\.authentication(?:Tag|Key)\\s*\\(|\\.tag\\s*\\(|\\.key\\s*\\(|opaqueContext)"
	);

	@Test
	void generalProtocolRemainsV2AndRawSeedModeRemainsFailClosed() throws Exception {
		assertEquals(2, WorldgenProtocolVersion.CURRENT.value());
		assertFalse(RemoteWorldgenConfig.current().remoteExecutionEnabled());

		String serverPayloads = Files.readString(Path.of("src/main/java/io/github/genichimaruo/worldgenassist/network/WorldgenPayloadTypes.java"));
		String clientWorker = Files.readString(Path.of("src/client/java/io/github/genichimaruo/worldgenassist/client/ClientWorldgenWorker.java"));
		assertFalse(serverPayloads.contains("SeededLeaf"));
		assertFalse(clientWorker.contains("SeededLeaf"));
	}

	@Test
	void fixtureRegistrationAndLifecycleRequireSeparateExplicitGates() throws Exception {
		String root = "src/main/java/io/github/genichimaruo/worldgenassist/";
		String payloads = Files.readString(Path.of(root + "network/SeededLeafFixturePayloads.java"));
		assertTrue(payloads.contains("if (!SeededLeafFixtureConfig.payloadsEnabled() || registered) { return; }"));
		assertTrue(payloads.contains("registerLarge(Request.TYPE, Request.CODEC, AuthorizedSeededLeafJobCodec.MAX_ENCODED_BYTES)"));
		assertTrue(payloads.contains("registerLarge(Result.TYPE, Result.CODEC, SeededLeafDensityResultEnvelopeCodec.MAX_ENCODED_BYTES)"));
		String manager = Files.readString(Path.of(root + "server/SeededLeafFixtureManager.java"));
		assertTrue(manager.indexOf("SeededLeafFixtureConfig.permitsWorld(") < manager.indexOf("ledger.open(path)"));
		assertTrue(manager.contains("if (!SeededLeafFixtureConfig.serverEnabled()) { return; }"));
		String initializer = Files.readString(Path.of(root + "WorldgenAssist.java"));
		assertTrue(initializer.contains("SeededLeafFixtureConfig.serverEnabled() && remoteConfig.remoteExecutionEnabled()"));
		assertTrue(initializer.contains("} else {\n\t\t\tSeededLeafJobSecurityLifecycle.register(remoteConfig);"));
	}

	@Test
	void trustedRawRequiresAnExplicitCurrentConfigurationGateAndLedgerRetainsCountersOnly() throws Exception {
		String enabled = System.getProperty(RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY);
		String disclosure = System.getProperty(RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY);
		try {
			System.setProperty(RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY, "true");
			System.clearProperty(RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY);
			assertFalse(RemoteWorldgenConfig.current().remoteExecutionEnabled());
			System.setProperty(RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY, "trusted_raw");
			assertTrue(RemoteWorldgenConfig.current().remoteExecutionEnabled());
		} finally {
			restore(RemoteWorldgenConfig.ENABLED_SYSTEM_PROPERTY, enabled);
			restore(RemoteWorldgenConfig.SEED_DISCLOSURE_MODE_SYSTEM_PROPERTY, disclosure);
		}

		assertEquals(72, SeededLeafPersistentDisclosureLedger.ENCODED_BYTES);
		assertTrue(Arrays.stream(SeededLeafPersistentDisclosureLedger.class.getDeclaredFields()).noneMatch(field -> {
			String type = field.getType().getName().toLowerCase(java.util.Locale.ROOT);
			String name = field.getName().toLowerCase(java.util.Locale.ROOT);
			return type.contains("transcript") || name.contains("seed") || name.contains("context")
				|| name.contains("authentication") || name.contains("tag") || name.contains("key");
		}));

		List<String> productionSources;
		try (Stream<Path> sources = Stream.concat(
			Files.walk(Path.of("src/main/java")), Files.walk(Path.of("src/client/java"))
		)) {
			productionSources = sources.filter(path -> path.toString().endsWith(".java"))
				.map(path -> {
					try {
						return Files.readString(path);
					} catch (java.io.IOException exception) {
						throw new java.io.UncheckedIOException(exception);
					}
				}).toList();
		}
		assertTrue(productionSources.stream().noneMatch(source -> source.contains("[CAWG] seed=")
			|| source.contains("[CAWG] transcript=") || source.contains("[CAWG] key=")
			|| source.contains("[CAWG] tag=") || source.contains("[CAWG] context=")));
		for (String source : productionSources) {
			Matcher matcher = LOGGER_CALL.matcher(source);
			while (matcher.find()) {
				assertFalse(SENSITIVE_LOG_VALUE.matcher(matcher.group()).find(),
					() -> "A production log invocation must not receive seed, transcript, key, tag, or opaque-context data");
			}
		}
	}

	private static void restore(String property, String value) {
		if (value == null) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, value);
		}
	}
}
