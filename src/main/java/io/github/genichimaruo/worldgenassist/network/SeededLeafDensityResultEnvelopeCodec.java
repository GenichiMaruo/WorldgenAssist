package io.github.genichimaruo.worldgenassist.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;

/** Strict bounded codec for the unregistered seeded-leaf result envelope. */
public final class SeededLeafDensityResultEnvelopeCodec {
	public static final int MAX_ENCODED_BYTES = SeededLeafDensityResultEnvelope.MAX_ENCODED_DENSITY_BYTES
		+ SeededLeafJobClaimCodec.ENCODED_BYTES + 32;
	public static final StreamCodec<RegistryFriendlyByteBuf, SeededLeafDensityResultEnvelope> CODEC = StreamCodec.of(
		SeededLeafDensityResultEnvelopeCodec::encode,
		SeededLeafDensityResultEnvelopeCodec::decode
	);

	private SeededLeafDensityResultEnvelopeCodec() {
	}

	private static void encode(RegistryFriendlyByteBuf buffer, SeededLeafDensityResultEnvelope envelope) {
		buffer.writeByte(SeededLeafDensityResultEnvelope.FORMAT_VERSION);
		SeededLeafJobClaimCodec.encodeFields(buffer, envelope.claim());
		buffer.writeVarInt(envelope.densityCount());
		buffer.writeByte(envelope.encoding().ordinal());
		byte[] encodedDensities = envelope.encodedDensities();
		buffer.writeVarInt(encodedDensities.length);
		buffer.writeBytes(encodedDensities);
		buffer.writeVarLong(envelope.clientComputeNanos());
		buffer.writeVarLong(envelope.clientEncodeNanos());
	}

	private static SeededLeafDensityResultEnvelope decode(RegistryFriendlyByteBuf buffer) {
		if (buffer.readableBytes() > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException(
				"Encoded seeded-leaf result exceeds " + MAX_ENCODED_BYTES + " bytes: " + buffer.readableBytes()
			);
		}
		int formatVersion = buffer.readUnsignedByte();
		if (formatVersion != SeededLeafDensityResultEnvelope.FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported seeded-leaf result format: " + formatVersion);
		}
		SeededLeafJobClaim claim = SeededLeafJobClaimCodec.decodeFields(buffer);
		int densityCount = buffer.readVarInt();
		if (densityCount < 1 || densityCount > TerrainDensityJob.MAX_SAMPLE_COUNT) {
			throw new IllegalArgumentException(
				"Encoded density count must be between 1 and " + TerrainDensityJob.MAX_SAMPLE_COUNT + ": " + densityCount
			);
		}
		int encodingId = buffer.readUnsignedByte();
		if (encodingId >= SeededLeafDensityResultEnvelope.Encoding.values().length) {
			throw new IllegalArgumentException("Unknown seeded-leaf density result encoding: " + encodingId);
		}
		int encodedLength = buffer.readVarInt();
		int rawLength = Math.multiplyExact(densityCount, SeededLeafDensityResultEnvelope.BYTES_PER_DENSITY);
		if (encodedLength < 1 || encodedLength > rawLength) {
			throw new IllegalArgumentException(
				"Encoded density length must be between 1 and " + rawLength + ": " + encodedLength
			);
		}
		byte[] encodedDensities = new byte[encodedLength];
		buffer.readBytes(encodedDensities);
		SeededLeafDensityResultEnvelope envelope = new SeededLeafDensityResultEnvelope(
			claim,
			densityCount,
			SeededLeafDensityResultEnvelope.Encoding.values()[encodingId],
			encodedDensities,
			buffer.readVarLong(),
			buffer.readVarLong()
		);
		if (buffer.isReadable()) {
			throw new IllegalArgumentException("Trailing bytes after seeded-leaf density result: " + buffer.readableBytes());
		}
		return envelope;
	}
}
