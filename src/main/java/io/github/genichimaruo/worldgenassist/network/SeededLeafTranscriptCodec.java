package io.github.genichimaruo.worldgenassist.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;

/** Binary codec for local protocol-v3 research. It is intentionally unregistered. */
public final class SeededLeafTranscriptCodec {
	public static final int MAX_ENCODED_BYTES = 4_000_000;
	private static final SeededLeafTranscript.Kind[] KINDS = SeededLeafTranscript.Kind.values();
	public static final StreamCodec<RegistryFriendlyByteBuf, SeededLeafTranscript> CODEC = StreamCodec.of(
		SeededLeafTranscriptCodec::encode,
		SeededLeafTranscriptCodec::decode
	);

	private SeededLeafTranscriptCodec() {
	}

	private static void encode(RegistryFriendlyByteBuf buffer, SeededLeafTranscript transcript) {
		buffer.writeByte(SeededLeafTranscript.FORMAT_VERSION);
		Map<String, Integer> dictionary = new LinkedHashMap<>();
		for (SeededLeafTranscript.Entry entry : transcript.entries()) {
			dictionary.computeIfAbsent(entry.leafId(), ignored -> dictionary.size());
		}
		buffer.writeVarInt(dictionary.size());
		for (String leafId : dictionary.keySet()) {
			WorldgenPayloadCodecs.writeBoundedUtf8(
				buffer,
				leafId,
				SeededLeafTranscript.MAX_LEAF_ID_UTF8_BYTES,
				"Seeded-leaf ID"
			);
		}
		buffer.writeVarInt(transcript.size());
		for (SeededLeafTranscript.Entry entry : transcript.entries()) {
			buffer.writeByte(entry.kind().ordinal());
			buffer.writeVarInt(dictionary.get(entry.leafId()));
			buffer.writeLong(entry.xBits());
			buffer.writeLong(entry.yBits());
			buffer.writeLong(entry.zBits());
			buffer.writeLong(entry.valueBits());
		}
	}

	private static SeededLeafTranscript decode(RegistryFriendlyByteBuf buffer) {
		if (buffer.readableBytes() > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException(
				"Encoded seeded-leaf transcript exceeds " + MAX_ENCODED_BYTES + " bytes: " + buffer.readableBytes()
			);
		}
		int format = buffer.readUnsignedByte();
		if (format != SeededLeafTranscript.FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported seeded-leaf transcript format: " + format);
		}
		int dictionaryCount = buffer.readVarInt();
		if (dictionaryCount < 1 || dictionaryCount > SeededLeafTranscript.MAX_DICTIONARY_ENTRIES) {
			throw new IllegalArgumentException(
				"Seeded-leaf dictionary count must be between 1 and "
					+ SeededLeafTranscript.MAX_DICTIONARY_ENTRIES + ": " + dictionaryCount
			);
		}
		List<String> dictionary = new ArrayList<>(dictionaryCount);
		Set<String> uniqueIds = new HashSet<>();
		for (int index = 0; index < dictionaryCount; index++) {
			String leafId = WorldgenPayloadCodecs.readBoundedUtf8(
				buffer,
				SeededLeafTranscript.MAX_LEAF_ID_UTF8_BYTES,
				"Seeded-leaf ID"
			);
			if (leafId.isBlank()) {
				throw new IllegalArgumentException("Seeded-leaf ID cannot be blank");
			}
			if (!uniqueIds.add(leafId)) {
				throw new IllegalArgumentException("Duplicate seeded-leaf dictionary ID: " + leafId);
			}
			dictionary.add(leafId);
		}

		int entryCount = buffer.readVarInt();
		if (entryCount < 1 || entryCount > SeededLeafTranscript.MAX_ENTRIES) {
			throw new IllegalArgumentException(
				"Seeded-leaf entry count must be between 1 and " + SeededLeafTranscript.MAX_ENTRIES + ": " + entryCount
			);
		}
		List<SeededLeafTranscript.Entry> entries = new ArrayList<>(entryCount);
		boolean[] usedDictionaryEntries = new boolean[dictionaryCount];
		int nextCanonicalDictionaryIndex = 0;
		for (int index = 0; index < entryCount; index++) {
			int kindId = buffer.readUnsignedByte();
			if (kindId >= KINDS.length) {
				throw new IllegalArgumentException("Unknown seeded-leaf kind: " + kindId);
			}
			int dictionaryIndex = buffer.readVarInt();
			if (dictionaryIndex < 0 || dictionaryIndex >= dictionaryCount) {
				throw new IllegalArgumentException("Seeded-leaf dictionary index is out of bounds: " + dictionaryIndex);
			}
			if (!usedDictionaryEntries[dictionaryIndex]) {
				if (dictionaryIndex != nextCanonicalDictionaryIndex) {
					throw new IllegalArgumentException(
						"Non-canonical first seeded-leaf dictionary use: " + dictionaryIndex
							+ " != " + nextCanonicalDictionaryIndex
					);
				}
				usedDictionaryEntries[dictionaryIndex] = true;
				nextCanonicalDictionaryIndex++;
			}
			entries.add(new SeededLeafTranscript.Entry(
				KINDS[kindId],
				dictionary.get(dictionaryIndex),
				buffer.readLong(),
				buffer.readLong(),
				buffer.readLong(),
				buffer.readLong()
			));
		}
		if (nextCanonicalDictionaryIndex != dictionaryCount) {
			throw new IllegalArgumentException(
				"Seeded-leaf transcript uses " + nextCanonicalDictionaryIndex + " of " + dictionaryCount + " dictionary entries"
			);
		}
		if (buffer.isReadable()) {
			throw new IllegalArgumentException("Seeded-leaf transcript contains " + buffer.readableBytes() + " trailing bytes");
		}
		return new SeededLeafTranscript(entries);
	}
}
