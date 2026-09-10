package io.github.genichimaruo.worldgenassist.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

public final class NoiseStageDigest {
	public static final int FORMAT_VERSION = 1;
	public static final String ALGORITHM = "SHA-256";
	private static final String FORMAT_NAME = "worldgen_assist:noise_stage";
	private static final Heightmap.Types[] HEIGHTMAP_TYPES = {
		Heightmap.Types.WORLD_SURFACE_WG,
		Heightmap.Types.OCEAN_FLOOR_WG
	};

	private NoiseStageDigest() {
	}

	public static Result compute(ChunkAccess chunk) {
		Objects.requireNonNull(chunk, "chunk");
		MessageDigest digest = newDigest();
		Map<BlockState, byte[]> canonicalStates = new IdentityHashMap<>();

		putUtf8(digest, FORMAT_NAME);
		putInt(digest, FORMAT_VERSION);
		putInt(digest, chunk.getPos().x());
		putInt(digest, chunk.getPos().z());
		putInt(digest, chunk.getMinY());
		putInt(digest, chunk.getHeight());

		LevelChunkSection[] sections = chunk.getSections();
		putInt(digest, sections.length);
		int blockCount = 0;
		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			putInt(digest, chunk.getSectionYFromSectionIndex(sectionIndex));
			LevelChunkSection section = sections[sectionIndex];
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						byte[] canonicalState = canonicalStates.computeIfAbsent(
							state,
							value -> canonicalBlockState(value).getBytes(StandardCharsets.UTF_8)
						);
						putBytes(digest, canonicalState);
						blockCount++;
					}
				}
			}
		}

		Map<Heightmap.Types, Heightmap> heightmaps = new EnumMap<>(Heightmap.Types.class);
		for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
			heightmaps.put(entry.getKey(), entry.getValue());
		}

		int heightmapLongCount = 0;
		putInt(digest, HEIGHTMAP_TYPES.length);
		for (Heightmap.Types type : HEIGHTMAP_TYPES) {
			putUtf8(digest, type.getSerializationKey());
			Heightmap heightmap = heightmaps.get(type);
			if (heightmap == null) {
				putByte(digest, 0);
				continue;
			}

			putByte(digest, 1);
			long[] rawData = heightmap.getRawData();
			putInt(digest, rawData.length);
			for (long value : rawData) {
				putLong(digest, value);
				heightmapLongCount++;
			}
		}

		int postProcessingCount = putPostProcessing(digest, chunk.getPostProcessing());
		return new Result(
			FORMAT_VERSION,
			ALGORITHM,
			HexFormat.of().formatHex(digest.digest()),
			blockCount,
			heightmapLongCount,
			postProcessingCount
		);
	}

	static String canonicalBlockState(BlockState state) {
		Identifier blockId = Objects.requireNonNull(BuiltInRegistries.BLOCK.getKey(state.getBlock()), "unregistered block");
		List<Property.Value<?>> values = state.getValues()
			.sorted(Comparator.comparing(value -> value.property().getName()))
			.toList();
		if (values.isEmpty()) {
			return blockId.toString();
		}

		StringBuilder result = new StringBuilder(blockId.toString()).append('[');
		for (int index = 0; index < values.size(); index++) {
			if (index > 0) {
				result.append(',');
			}

			Property.Value<?> value = values.get(index);
			result.append(value.property().getName()).append('=').append(value.valueName());
		}

		return result.append(']').toString();
	}

	private static int putPostProcessing(MessageDigest digest, ShortList[] postProcessing) {
		if (postProcessing == null) {
			putInt(digest, -1);
			return 0;
		}

		putInt(digest, postProcessing.length);
		int total = 0;
		for (int sectionIndex = 0; sectionIndex < postProcessing.length; sectionIndex++) {
			putInt(digest, sectionIndex);
			ShortList section = postProcessing[sectionIndex];
			if (section == null) {
				putInt(digest, 0);
				continue;
			}

			int[] offsets = new int[section.size()];
			for (int index = 0; index < section.size(); index++) {
				offsets[index] = section.getShort(index) & 0xFFFF;
			}

			Arrays.sort(offsets);
			putInt(digest, offsets.length);
			for (int offset : offsets) {
				putInt(digest, offset);
				total++;
			}
		}

		return total;
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance(ALGORITHM);
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(ALGORITHM + " is required by the Java runtime", error);
		}
	}

	private static void putUtf8(MessageDigest digest, String value) {
		putBytes(digest, value.getBytes(StandardCharsets.UTF_8));
	}

	private static void putBytes(MessageDigest digest, byte[] value) {
		putInt(digest, value.length);
		digest.update(value);
	}

	private static void putByte(MessageDigest digest, int value) {
		digest.update((byte)value);
	}

	private static void putInt(MessageDigest digest, int value) {
		putByte(digest, value >>> 24);
		putByte(digest, value >>> 16);
		putByte(digest, value >>> 8);
		putByte(digest, value);
	}

	private static void putLong(MessageDigest digest, long value) {
		putByte(digest, (int)(value >>> 56));
		putByte(digest, (int)(value >>> 48));
		putByte(digest, (int)(value >>> 40));
		putByte(digest, (int)(value >>> 32));
		putByte(digest, (int)(value >>> 24));
		putByte(digest, (int)(value >>> 16));
		putByte(digest, (int)(value >>> 8));
		putByte(digest, (int)value);
	}

	public record Result(
		int formatVersion,
		String algorithm,
		String digest,
		int blockCount,
		int heightmapLongCount,
		int postProcessingCount
	) {
	}
}
