package io.github.genichimaruo.worldgenassist.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Immutable wire-ready values for bounded replay of seed-dependent leaves. */
public record SeededLeafTranscript(List<Entry> entries) {
	public static final int FORMAT_VERSION = 1;
	public static final int MAX_ENTRIES = 100_000;
	public static final int MAX_DICTIONARY_ENTRIES = 64;
	public static final int MAX_LEAF_ID_UTF8_BYTES = 320;

	public SeededLeafTranscript {
		Objects.requireNonNull(entries, "entries");
		if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
			throw new IllegalArgumentException("Transcript entries must be between 1 and " + MAX_ENTRIES + ": " + entries.size());
		}
		entries = List.copyOf(entries);
		long distinctLeafIds = entries.stream().map(Entry::leafId).distinct().count();
		if (distinctLeafIds > MAX_DICTIONARY_ENTRIES) {
			throw new IllegalArgumentException(
				"Transcript leaf dictionary exceeds " + MAX_DICTIONARY_ENTRIES + " entries: " + distinctLeafIds
			);
		}
	}

	public int size() {
		return entries.size();
	}

	public enum Kind {
		NORMAL_NOISE,
		BLENDED_NOISE
	}

	public record Entry(Kind kind, String leafId, long xBits, long yBits, long zBits, long valueBits) {
		public Entry {
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(leafId, "leafId");
			int encodedBytes = leafId.getBytes(StandardCharsets.UTF_8).length;
			if (leafId.isBlank() || encodedBytes > MAX_LEAF_ID_UTF8_BYTES) {
				throw new IllegalArgumentException("Invalid seeded-leaf ID UTF-8 length: " + encodedBytes);
			}
		}
	}
}
