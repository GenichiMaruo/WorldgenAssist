package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SeededLeafTranscriptTest {
	@Test
	void copiesEntriesAndPreservesExactBits() {
		ArrayList<SeededLeafTranscript.Entry> entries = new ArrayList<>();
		entries.add(entry("normal:minecraft:test", 1L));
		SeededLeafTranscript transcript = new SeededLeafTranscript(entries);
		entries.clear();

		assertEquals(1, transcript.size());
		assertEquals(1L, transcript.entries().getFirst().valueBits());
		assertThrows(UnsupportedOperationException.class, () -> transcript.entries().clear());
	}

	@Test
	void rejectsEmptyOversizedDictionaryAndInvalidLeafIds() {
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafTranscript(List.of()));
		assertThrows(
			IllegalArgumentException.class,
			() -> new SeededLeafTranscript(List.of(new SeededLeafTranscript.Entry(
				SeededLeafTranscript.Kind.NORMAL_NOISE,
				"x".repeat(SeededLeafTranscript.MAX_LEAF_ID_UTF8_BYTES + 1),
				0L,
				0L,
				0L,
				0L
			)))
		);

		List<SeededLeafTranscript.Entry> tooManyIds = new ArrayList<>();
		for (int index = 0; index <= SeededLeafTranscript.MAX_DICTIONARY_ENTRIES; index++) {
			tooManyIds.add(entry("normal:minecraft:test_" + index, index));
		}
		assertThrows(IllegalArgumentException.class, () -> new SeededLeafTranscript(tooManyIds));
	}

	private static SeededLeafTranscript.Entry entry(String leafId, long valueBits) {
		return new SeededLeafTranscript.Entry(
			SeededLeafTranscript.Kind.NORMAL_NOISE,
			leafId,
			Double.doubleToRawLongBits(1.0),
			Double.doubleToRawLongBits(2.0),
			Double.doubleToRawLongBits(3.0),
			valueBits
		);
	}
}
