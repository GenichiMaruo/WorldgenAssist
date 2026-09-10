package io.github.genichimaruo.worldgenassist.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class WorldgenContextFingerprintFactory {
	public static final int FORMAT_VERSION = 1;

	private static final String DOMAIN = "worldgen_assist:worldgen_context";

	private WorldgenContextFingerprintFactory() {
	}

	public static WorldgenContextFingerprint create(ServerLevel level, NoiseBasedChunkGenerator generator) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(generator, "generator");
		return fingerprint(capture(level, generator));
	}

	public static WorldgenContextFingerprint create(
		HolderLookup.Provider registries,
		Identifier dimension,
		long worldSeed,
		boolean generateStructures,
		int minY,
		int height,
		Holder<NoiseGeneratorSettings> settings
	) {
		return fingerprint(capture(registries, dimension, worldSeed, generateStructures, minY, height, settings));
	}

	static ContextSnapshot capture(ServerLevel level, NoiseBasedChunkGenerator generator) {
		WorldOptions worldOptions = level.getServer().getWorldGenSettings().options();
		return capture(
			level.registryAccess(),
			level.dimension().identifier(),
			worldOptions.seed(),
			worldOptions.generateStructures(),
			level.getMinY(),
			level.getHeight(),
			generator.generatorSettings()
		);
	}

	static ContextSnapshot capture(
		HolderLookup.Provider registries,
		Identifier dimension,
		long worldSeed,
		boolean generateStructures,
		int minY,
		int height,
		Holder<NoiseGeneratorSettings> settings
	) {
		Objects.requireNonNull(registries, "registries");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(settings, "settings");
		DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		WorldVersion gameVersion = SharedConstants.getCurrentVersion();
		return new ContextSnapshot(
			gameVersion.id(),
			gameVersion.dataVersion().version(),
			gameVersion.dataVersion().series(),
			gameVersion.protocolVersion(),
			dimension,
			worldSeed,
			generateStructures,
			minY,
			height,
			settings.unwrapKey().map(ResourceKey::identifier),
			encode(NoiseGeneratorSettings.DIRECT_CODEC, ops, settings.value(), "selected noise settings"),
			encodeRegistry(registries, Registries.DENSITY_FUNCTION, DensityFunctions.DIRECT_CODEC, ops),
			encodeRegistry(registries, Registries.NOISE, NormalNoise.NoiseParameters.DIRECT_CODEC, ops),
			SharedConstants.DEBUG_AQUIFERS,
			SharedConstants.DEBUG_DISABLE_AQUIFERS,
			SharedConstants.DEBUG_DISABLE_FLUID_GENERATION,
			SharedConstants.DEBUG_DISABLE_ORE_VEINS,
			SharedConstants.DEBUG_ONLY_GENERATE_HALF_THE_WORLD
		);
	}

	static WorldgenContextFingerprint fingerprint(ContextSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		CanonicalDigestWriter writer = new CanonicalDigestWriter();
		writer.putString(DOMAIN);
		writer.putInt(FORMAT_VERSION);
		writer.putInt(WorldgenProtocolVersion.CURRENT.value());
		writer.putString(snapshot.gameVersionId());
		writer.putInt(snapshot.dataVersion());
		writer.putString(snapshot.dataVersionSeries());
		writer.putInt(snapshot.gameProtocolVersion());
		writer.putString(snapshot.dimension().toString());
		writer.putLong(snapshot.worldSeed());
		writer.putBoolean(snapshot.generateStructures());
		writer.putInt(snapshot.minY());
		writer.putInt(snapshot.height());
		writer.putBoolean(snapshot.noiseSettingsKey().isPresent());
		snapshot.noiseSettingsKey().ifPresent(key -> writer.putString(key.toString()));
		writer.putJson(snapshot.noiseSettings());
		writer.putRegistry("density_function", snapshot.densityFunctions());
		writer.putRegistry("noise", snapshot.noiseParameters());
		writer.putBoolean(snapshot.debugAquifers());
		writer.putBoolean(snapshot.debugDisableAquifers());
		writer.putBoolean(snapshot.debugDisableFluidGeneration());
		writer.putBoolean(snapshot.debugDisableOreVeins());
		writer.putBoolean(snapshot.debugOnlyGenerateHalfTheWorld());
		return WorldgenContextFingerprint.fromBytes(writer.finish());
	}

	private static <T> List<EncodedRegistryEntry> encodeRegistry(
		HolderLookup.Provider registries,
		ResourceKey<? extends net.minecraft.core.Registry<T>> registryKey,
		Codec<T> codec,
		DynamicOps<JsonElement> ops
	) {
		HolderLookup.RegistryLookup<T> registry = registries.lookupOrThrow(registryKey);
		return registry.listElements()
			.sorted(Comparator.comparing(entry -> entry.key().identifier().toString()))
			.map(entry -> new EncodedRegistryEntry(
				entry.key().identifier(),
				encode(codec, ops, entry.value(), registryKey.identifier() + "/" + entry.key().identifier())
			))
			.toList();
	}

	private static <T> JsonElement encode(Codec<T> codec, DynamicOps<JsonElement> ops, T value, String description) {
		try {
			return codec.encodeStart(ops, value).getOrThrow();
		} catch (RuntimeException exception) {
			throw new IllegalStateException("Unable to encode " + description + " for worldgen context fingerprint", exception);
		}
	}

	static record ContextSnapshot(
		String gameVersionId,
		int dataVersion,
		String dataVersionSeries,
		int gameProtocolVersion,
		Identifier dimension,
		long worldSeed,
		boolean generateStructures,
		int minY,
		int height,
		Optional<Identifier> noiseSettingsKey,
		JsonElement noiseSettings,
		List<EncodedRegistryEntry> densityFunctions,
		List<EncodedRegistryEntry> noiseParameters,
		boolean debugAquifers,
		boolean debugDisableAquifers,
		boolean debugDisableFluidGeneration,
		boolean debugDisableOreVeins,
		boolean debugOnlyGenerateHalfTheWorld
	) {
		ContextSnapshot {
			Objects.requireNonNull(gameVersionId, "gameVersionId");
			Objects.requireNonNull(dataVersionSeries, "dataVersionSeries");
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(noiseSettingsKey, "noiseSettingsKey");
			Objects.requireNonNull(noiseSettings, "noiseSettings");
			densityFunctions = List.copyOf(densityFunctions);
			noiseParameters = List.copyOf(noiseParameters);
			if (height <= 0) {
				throw new IllegalArgumentException("height must be positive: " + height);
			}
		}
	}

	static record EncodedRegistryEntry(Identifier id, JsonElement value) {
		EncodedRegistryEntry {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(value, "value");
		}
	}

	private static final class CanonicalDigestWriter {
		private static final byte NULL_TAG = 0;
		private static final byte BOOLEAN_TAG = 1;
		private static final byte NUMBER_TAG = 2;
		private static final byte STRING_TAG = 3;
		private static final byte ARRAY_TAG = 4;
		private static final byte OBJECT_TAG = 5;

		private final MessageDigest digest;

		private CanonicalDigestWriter() {
			try {
				this.digest = MessageDigest.getInstance(WorldgenContextFingerprint.ALGORITHM);
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException(WorldgenContextFingerprint.ALGORITHM + " is unavailable", exception);
			}
		}

		private void putRegistry(String name, List<EncodedRegistryEntry> entries) {
			putString(name);
			List<EncodedRegistryEntry> sorted = entries.stream().sorted(Comparator.comparing(entry -> entry.id().toString())).toList();
			putInt(sorted.size());
			String previousId = null;
			for (EncodedRegistryEntry entry : sorted) {
				String id = entry.id().toString();
				if (id.equals(previousId)) {
					throw new IllegalArgumentException("Duplicate canonical registry entry: " + name + "/" + id);
				}
				putString(id);
				putJson(entry.value());
				previousId = id;
			}
		}

		private void putJson(JsonElement value) {
			if (value.isJsonNull()) {
				putByte(NULL_TAG);
			} else if (value.isJsonArray()) {
				putByte(ARRAY_TAG);
				putInt(value.getAsJsonArray().size());
				value.getAsJsonArray().forEach(this::putJson);
			} else if (value.isJsonObject()) {
				putByte(OBJECT_TAG);
				JsonObject object = value.getAsJsonObject();
				List<Map.Entry<String, JsonElement>> fields = object.entrySet()
					.stream()
					.sorted(Map.Entry.comparingByKey())
					.toList();
				putInt(fields.size());
				for (Map.Entry<String, JsonElement> field : fields) {
					putString(field.getKey());
					putJson(field.getValue());
				}
			} else {
				JsonPrimitive primitive = value.getAsJsonPrimitive();
				if (primitive.isBoolean()) {
					putByte(BOOLEAN_TAG);
					putBoolean(primitive.getAsBoolean());
				} else if (primitive.isNumber()) {
					putByte(NUMBER_TAG);
					putString(primitive.getAsString());
				} else if (primitive.isString()) {
					putByte(STRING_TAG);
					putString(primitive.getAsString());
				} else {
					throw new IllegalArgumentException("Unsupported JSON primitive: " + primitive);
				}
			}
		}

		private void putString(String value) {
			byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
			putInt(bytes.length);
			digest.update(bytes);
		}

		private void putBoolean(boolean value) {
			putByte(value ? (byte)1 : (byte)0);
		}

		private void putLong(long value) {
			putInt((int)(value >>> 32));
			putInt((int)value);
		}

		private void putInt(int value) {
			putByte((byte)(value >>> 24));
			putByte((byte)(value >>> 16));
			putByte((byte)(value >>> 8));
			putByte((byte)value);
		}

		private void putByte(byte value) {
			digest.update(value);
		}

		private byte[] finish() {
			return digest.digest();
		}
	}
}
