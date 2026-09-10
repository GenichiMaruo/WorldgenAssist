package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import org.junit.jupiter.api.Test;

class WorldgenContextFingerprintFactoryTest {
	@Test
	void canonicalizesJsonObjectAndRegistryEntryOrder() {
		WorldgenContextFingerprint first = fingerprint(
			8675309L,
			Identifier.parse("minecraft:overworld"),
			json("{\"second\":2,\"first\":1}"),
			List.of(entry("test:z", "{\"b\":2,\"a\":1}"), entry("test:a", "3")),
			List.of(entry("test:noise", "{\"amplitudes\":[1.0,0.5]}"))
		);
		WorldgenContextFingerprint reordered = fingerprint(
			8675309L,
			Identifier.parse("minecraft:overworld"),
			json("{\"first\":1,\"second\":2}"),
			List.of(entry("test:a", "3"), entry("test:z", "{\"a\":1,\"b\":2}")),
			List.of(entry("test:noise", "{\"amplitudes\":[1.0,0.5]}"))
		);

		assertEquals(first, reordered);
	}

	@Test
	void preservesJsonArrayOrder() {
		WorldgenContextFingerprint first = fingerprint(
			1L,
			Identifier.parse("minecraft:overworld"),
			json("{\"values\":[1,2]}"),
			List.of(),
			List.of()
		);
		WorldgenContextFingerprint reversed = fingerprint(
			1L,
			Identifier.parse("minecraft:overworld"),
			json("{\"values\":[2,1]}"),
			List.of(),
			List.of()
		);

		assertNotEquals(first, reversed);
	}

	@Test
	void changesWhenSeedDimensionSettingsOrRegistryContentChanges() {
		WorldgenContextFingerprint baseline = fingerprint(
			1L,
			Identifier.parse("minecraft:overworld"),
			json("{\"setting\":1}"),
			List.of(entry("test:density", "1")),
			List.of(entry("test:noise", "2"))
		);

		assertNotEquals(
			baseline,
			fingerprint(
				2L,
				Identifier.parse("minecraft:overworld"),
				json("{\"setting\":1}"),
				List.of(entry("test:density", "1")),
				List.of(entry("test:noise", "2"))
			)
		);
		assertNotEquals(
			baseline,
			fingerprint(
				1L,
				Identifier.parse("minecraft:the_nether"),
				json("{\"setting\":1}"),
				List.of(entry("test:density", "1")),
				List.of(entry("test:noise", "2"))
			)
		);
		assertNotEquals(
			baseline,
			fingerprint(
				1L,
				Identifier.parse("minecraft:overworld"),
				json("{\"setting\":2}"),
				List.of(entry("test:density", "1")),
				List.of(entry("test:noise", "2"))
			)
		);
		assertNotEquals(
			baseline,
			fingerprint(
				1L,
				Identifier.parse("minecraft:overworld"),
				json("{\"setting\":1}"),
				List.of(entry("test:density", "9")),
				List.of(entry("test:noise", "2"))
			)
		);
		assertNotEquals(
			baseline,
			fingerprint(
				1L,
				Identifier.parse("minecraft:overworld"),
				json("{\"setting\":1}"),
				List.of(entry("test:density", "1")),
				List.of(entry("test:noise", "9"))
			)
		);
	}

	@Test
	void rejectsDuplicateCanonicalRegistryKeys() {
		WorldgenContextFingerprintFactory.ContextSnapshot snapshot = snapshot(
			1L,
			Identifier.parse("minecraft:overworld"),
			json("{}"),
			List.of(entry("test:duplicate", "1"), entry("test:duplicate", "2")),
			List.of()
		);

		assertThrows(IllegalArgumentException.class, () -> WorldgenContextFingerprintFactory.fingerprint(snapshot));
	}

	@Test
	void producesAStableFormatOneGoldenVector() {
		WorldgenContextFingerprint fingerprint = fingerprint(
			8675309L,
			Identifier.parse("minecraft:overworld"),
			json("{\"first\":1,\"second\":2}"),
			List.of(entry("test:a", "3"), entry("test:z", "{\"a\":1,\"b\":2}")),
			List.of(entry("test:noise", "{\"amplitudes\":[1.0,0.5]}"))
		);

		assertEquals("513161b350fa853c37e4b58f2e5ddfc79c50139ce1860f062702f00becd7d20c", fingerprint.toHex());
	}

	private static WorldgenContextFingerprint fingerprint(
		long seed,
		Identifier dimension,
		JsonElement noiseSettings,
		List<WorldgenContextFingerprintFactory.EncodedRegistryEntry> densityFunctions,
		List<WorldgenContextFingerprintFactory.EncodedRegistryEntry> noiseParameters
	) {
		return WorldgenContextFingerprintFactory.fingerprint(snapshot(seed, dimension, noiseSettings, densityFunctions, noiseParameters));
	}

	private static WorldgenContextFingerprintFactory.ContextSnapshot snapshot(
		long seed,
		Identifier dimension,
		JsonElement noiseSettings,
		List<WorldgenContextFingerprintFactory.EncodedRegistryEntry> densityFunctions,
		List<WorldgenContextFingerprintFactory.EncodedRegistryEntry> noiseParameters
	) {
		return new WorldgenContextFingerprintFactory.ContextSnapshot(
			"26.2-test",
			4671,
			"main",
			776,
			dimension,
			seed,
			true,
			-64,
			384,
			Optional.of(Identifier.parse("minecraft:overworld")),
			noiseSettings,
			densityFunctions,
			noiseParameters,
			false,
			false,
			false,
			false,
			false
		);
	}

	private static WorldgenContextFingerprintFactory.EncodedRegistryEntry entry(String id, String value) {
		return new WorldgenContextFingerprintFactory.EncodedRegistryEntry(Identifier.parse(id), json(value));
	}

	private static JsonElement json(String value) {
		return JsonParser.parseString(value);
	}
}
