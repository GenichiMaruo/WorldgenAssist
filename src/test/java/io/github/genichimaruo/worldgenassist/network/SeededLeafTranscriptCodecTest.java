package io.github.genichimaruo.worldgenassist.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;
import org.junit.jupiter.api.Test;

class SeededLeafTranscriptCodecTest {
	@Test
	void roundTripsKindsRepeatedIdsAndRawDoubleBits() {
		SeededLeafTranscript transcript = new SeededLeafTranscript(List.of(
			entry(SeededLeafTranscript.Kind.NORMAL_NOISE, "normal:minecraft:temperature", 1L),
			entry(SeededLeafTranscript.Kind.BLENDED_NOISE, "blended:0", 2L),
			entry(SeededLeafTranscript.Kind.NORMAL_NOISE, "normal:minecraft:temperature", 3L)
		));

		assertEquals(transcript, roundTrip(transcript));
	}

	@Test
	void maximumTranscriptFitsTheExplicitEncodedBound() {
		List<SeededLeafTranscript.Entry> entries = new ArrayList<>(SeededLeafTranscript.MAX_ENTRIES);
		for (int index = 0; index < SeededLeafTranscript.MAX_ENTRIES; index++) {
			entries.add(entry(
				index % 17 == 0 ? SeededLeafTranscript.Kind.BLENDED_NOISE : SeededLeafTranscript.Kind.NORMAL_NOISE,
				"normal:minecraft:test_" + (index % SeededLeafTranscript.MAX_DICTIONARY_ENTRIES),
				index
			));
		}
		SeededLeafTranscript transcript = new SeededLeafTranscript(entries);
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SeededLeafTranscriptCodec.CODEC.encode(buffer, transcript);
			assertTrue(buffer.readableBytes() <= SeededLeafTranscriptCodec.MAX_ENCODED_BYTES);
			assertEquals(transcript, SeededLeafTranscriptCodec.CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes());
		} finally {
			buffer.release();
		}
	}

	@Test
	void rejectsEveryTruncationOfAValidTranscript() {
		SeededLeafTranscript transcript = new SeededLeafTranscript(List.of(
			entry(SeededLeafTranscript.Kind.NORMAL_NOISE, "normal:minecraft:test", 1L),
			entry(SeededLeafTranscript.Kind.BLENDED_NOISE, "blended:0", 2L)
		));
		byte[] encoded = encode(transcript);

		for (int length = 0; length < encoded.length; length++) {
			byte[] truncated = Arrays.copyOf(encoded, length);
			RegistryFriendlyByteBuf buffer = wrappedBuffer(truncated);
			try {
				assertThrows(RuntimeException.class, () -> SeededLeafTranscriptCodec.CODEC.decode(buffer), "length=" + length);
			} finally {
				buffer.release();
			}
		}
	}

	@Test
	void rejectsMalformedBoundsKindsAndDictionaryForms() {
		assertMalformed(buffer -> buffer.writeByte(255));
		assertMalformed(buffer -> {
			buffer.writeByte(SeededLeafTranscript.FORMAT_VERSION);
			buffer.writeVarInt(SeededLeafTranscript.MAX_DICTIONARY_ENTRIES + 1);
		});
		assertMalformed(buffer -> {
			writeDictionaryHeader(buffer, "normal:minecraft:test", "normal:minecraft:test");
		});
		assertMalformed(buffer -> {
			writeDictionaryHeader(buffer, "normal:minecraft:test");
			buffer.writeVarInt(SeededLeafTranscript.MAX_ENTRIES + 1);
		});
		assertMalformed(buffer -> {
			writeDictionaryHeader(buffer, "normal:minecraft:test");
			buffer.writeVarInt(1);
			buffer.writeByte(255);
		});
		assertMalformed(buffer -> {
			writeDictionaryHeader(buffer, "normal:minecraft:test");
			buffer.writeVarInt(1);
			writeWireEntry(buffer, 0, 1);
		});
		assertMalformed(buffer -> {
			writeDictionaryHeader(buffer, "normal:minecraft:first", "normal:minecraft:second");
			buffer.writeVarInt(1);
			writeWireEntry(buffer, 0, 1);
		});
		assertMalformed(buffer -> {
			writeDictionaryHeader(buffer, "normal:minecraft:first", "normal:minecraft:unused");
			buffer.writeVarInt(1);
			writeWireEntry(buffer, 0, 0);
		});
	}

	@Test
	void rejectsInputBeyondEncodedByteBoundBeforeParsing() {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
			Unpooled.buffer(SeededLeafTranscriptCodec.MAX_ENCODED_BYTES + 1),
			RegistryAccess.EMPTY
		);
		try {
			buffer.writeZero(SeededLeafTranscriptCodec.MAX_ENCODED_BYTES + 1);
			assertThrows(IllegalArgumentException.class, () -> SeededLeafTranscriptCodec.CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	@Test
	void rejectsTrailingBytes() {
		byte[] encoded = encode(new SeededLeafTranscript(List.of(
			entry(SeededLeafTranscript.Kind.NORMAL_NOISE, "normal:minecraft:test", 1L)
		)));
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			buffer.writeBytes(encoded);
			buffer.writeByte(0);
			assertThrows(IllegalArgumentException.class, () -> SeededLeafTranscriptCodec.CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	@Test
	void boundedRandomInputNeverEscapesWithAnError() {
		assertDoesNotThrow(() -> {
			Random random = new Random(0x5EED_0003L);
			for (int iteration = 0; iteration < 2_000; iteration++) {
				byte[] bytes = new byte[random.nextInt(513)];
				random.nextBytes(bytes);
				RegistryFriendlyByteBuf buffer = wrappedBuffer(bytes);
				try {
					try {
						SeededLeafTranscript decoded = SeededLeafTranscriptCodec.CODEC.decode(buffer);
						assertTrue(decoded.size() <= SeededLeafTranscript.MAX_ENTRIES);
					} catch (RuntimeException expectedMalformedInput) {
						// Random input may fail at any bounded field.
					}
				} finally {
					buffer.release();
				}
			}
		});
	}

	private static SeededLeafTranscript.Entry entry(SeededLeafTranscript.Kind kind, String leafId, long valueBits) {
		return new SeededLeafTranscript.Entry(kind, leafId, 1L, 2L, 3L, valueBits);
	}

	private static SeededLeafTranscript roundTrip(SeededLeafTranscript transcript) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SeededLeafTranscriptCodec.CODEC.encode(buffer, transcript);
			SeededLeafTranscript decoded = SeededLeafTranscriptCodec.CODEC.decode(buffer);
			assertEquals(0, buffer.readableBytes());
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private static byte[] encode(SeededLeafTranscript transcript) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			SeededLeafTranscriptCodec.CODEC.encode(buffer, transcript);
			byte[] encoded = new byte[buffer.readableBytes()];
			buffer.readBytes(encoded);
			return encoded;
		} finally {
			buffer.release();
		}
	}

	private static void assertMalformed(java.util.function.Consumer<RegistryFriendlyByteBuf> writer) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			writer.accept(buffer);
			assertThrows(RuntimeException.class, () -> SeededLeafTranscriptCodec.CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	private static void writeDictionaryHeader(RegistryFriendlyByteBuf buffer, String... ids) {
		buffer.writeByte(SeededLeafTranscript.FORMAT_VERSION);
		buffer.writeVarInt(ids.length);
		for (String id : ids) {
			WorldgenPayloadCodecs.writeBoundedUtf8(
				buffer,
				id,
				SeededLeafTranscript.MAX_LEAF_ID_UTF8_BYTES,
				"Seeded-leaf ID"
			);
		}
	}

	private static void writeWireEntry(RegistryFriendlyByteBuf buffer, int kind, int dictionaryIndex) {
		buffer.writeByte(kind);
		buffer.writeVarInt(dictionaryIndex);
		buffer.writeLong(1L);
		buffer.writeLong(2L);
		buffer.writeLong(3L);
		buffer.writeLong(4L);
	}

	private static RegistryFriendlyByteBuf wrappedBuffer(byte[] bytes) {
		return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}
}
