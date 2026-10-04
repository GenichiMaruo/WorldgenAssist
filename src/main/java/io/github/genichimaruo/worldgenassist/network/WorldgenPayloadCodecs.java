package io.github.genichimaruo.worldgenassist.network;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainWorkKind;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;

final class WorldgenPayloadCodecs {
	private WorldgenPayloadCodecs() {
	}

	static void writeIdentity(RegistryFriendlyByteBuf buffer, TerrainJobIdentity identity) {
		buffer.writeVarInt(identity.protocolVersion().value());
		buffer.writeUUID(identity.jobId());
		writeIdentifier(buffer, identity.dimension(), TerrainJobIdentity.MAX_DIMENSION_ID_UTF8_BYTES);
		buffer.writeInt(identity.chunkX());
		buffer.writeInt(identity.chunkZ());
		buffer.writeBytes(identity.contextFingerprint().bytes());
	}

	static TerrainJobIdentity readIdentity(RegistryFriendlyByteBuf buffer) {
		WorldgenProtocolVersion protocolVersion = new WorldgenProtocolVersion(buffer.readVarInt());
		UUID jobId = buffer.readUUID();
		Identifier dimension = readIdentifier(buffer, TerrainJobIdentity.MAX_DIMENSION_ID_UTF8_BYTES);
		int chunkX = buffer.readInt();
		int chunkZ = buffer.readInt();
		byte[] fingerprint = new byte[WorldgenContextFingerprint.BYTE_LENGTH];
		buffer.readBytes(fingerprint);
		return new TerrainJobIdentity(
			protocolVersion,
			jobId,
			dimension,
			chunkX,
			chunkZ,
			WorldgenContextFingerprint.fromBytes(fingerprint)
		);
	}

	static void writeJob(RegistryFriendlyByteBuf buffer, TerrainDensityJob job) {
		writeIdentity(buffer, job.identity());
		buffer.writeLong(job.worldSeed());
		buffer.writeBoolean(job.generateStructures());
		writeIdentifier(buffer, job.noiseSettings(), TerrainDensityJob.MAX_NOISE_SETTINGS_ID_UTF8_BYTES);
		buffer.writeInt(job.minY());
		buffer.writeVarInt(job.height());
		buffer.writeVarInt(job.cellWidth());
		buffer.writeVarInt(job.cellHeight());
		buffer.writeByte(job.workKind().ordinal());
		if (job.workKind() == TerrainWorkKind.COMPLETE_TERRAIN) {
			byte[] shaping = job.shaping().encode(); buffer.writeVarInt(shaping.length); buffer.writeBytes(shaping);
		}
	}

	static TerrainDensityJob readJob(RegistryFriendlyByteBuf buffer) {
		TerrainDensityJob base = new TerrainDensityJob(
			readIdentity(buffer),
			buffer.readLong(),
			buffer.readBoolean(),
			readIdentifier(buffer, TerrainDensityJob.MAX_NOISE_SETTINGS_ID_UTF8_BYTES),
			buffer.readInt(),
			buffer.readVarInt(),
			buffer.readVarInt(),
			buffer.readVarInt(),
			TerrainWorkKind.fromWire(buffer.readUnsignedByte())
		);
		if (base.workKind() != TerrainWorkKind.COMPLETE_TERRAIN) return base;
		int length = buffer.readVarInt();
		if (length < 13 || length > io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData.MAX_BYTES) {
			throw new IllegalArgumentException("Invalid shaping body bound");
		}
		byte[] encoded = new byte[length]; buffer.readBytes(encoded);
		var shaping = io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData.decode(encoded);
		return new TerrainDensityJob(base.identity(), base.worldSeed(), base.generateStructures(), base.noiseSettings(),
			base.minY(), base.height(), base.cellWidth(), base.cellHeight(), base.workKind(), shaping);
	}

	static void writeResult(RegistryFriendlyByteBuf buffer, TerrainDensityResultEnvelope result) {
		writeIdentity(buffer, result.identity());
		buffer.writeVarInt(result.densityCount());
		buffer.writeByte(result.encoding().ordinal());
		byte[] encodedDensities = result.encodedDensities();
		buffer.writeVarInt(encodedDensities.length);
		buffer.writeBytes(encodedDensities);
		buffer.writeVarLong(result.clientComputeNanos());
		buffer.writeVarLong(result.clientEncodeNanos());
	}

	static TerrainDensityResultEnvelope readResult(RegistryFriendlyByteBuf buffer) {
		TerrainJobIdentity identity = readIdentity(buffer);
		int count = buffer.readVarInt();
		if (count < 1 || count > TerrainDensityJob.MAX_TERRAIN_SAMPLE_COUNT) {
			throw new IllegalArgumentException(
				"Encoded density count must be between 1 and " + TerrainDensityJob.MAX_TERRAIN_SAMPLE_COUNT + ": " + count
			);
		}
		int encodingId = buffer.readUnsignedByte();
		if (encodingId >= TerrainDensityResultEnvelope.Encoding.values().length) {
			throw new IllegalArgumentException("Unknown density result encoding: " + encodingId);
		}
		int encodedLength = buffer.readVarInt();
		int rawLength = TerrainDensityResultEnvelope.rawBytes(count, TerrainDensityResultEnvelope.Encoding.values()[encodingId]);
		if (encodedLength < 1 || encodedLength > rawLength) {
			throw new IllegalArgumentException(
				"Encoded density length must be between 1 and " + rawLength + ": " + encodedLength
			);
		}
		byte[] encodedDensities = new byte[encodedLength];
		buffer.readBytes(encodedDensities);
		return new TerrainDensityResultEnvelope(
			identity,
			count,
			TerrainDensityResultEnvelope.Encoding.values()[encodingId],
			encodedDensities,
			buffer.readVarLong(),
			buffer.readVarLong()
		);
	}

	static void writeBoundedUtf8(RegistryFriendlyByteBuf buffer, String value, int maxUtf8Bytes, String description) {
		int encodedBytes = value.getBytes(StandardCharsets.UTF_8).length;
		if (encodedBytes > maxUtf8Bytes) {
			throw new IllegalArgumentException(description + " exceeds " + maxUtf8Bytes + " UTF-8 bytes: " + encodedBytes);
		}
		buffer.writeUtf(value, maxUtf8Bytes);
	}

	static String readBoundedUtf8(RegistryFriendlyByteBuf buffer, int maxUtf8Bytes, String description) {
		String value = buffer.readUtf(maxUtf8Bytes);
		int encodedBytes = value.getBytes(StandardCharsets.UTF_8).length;
		if (encodedBytes > maxUtf8Bytes) {
			throw new IllegalArgumentException(description + " exceeds " + maxUtf8Bytes + " UTF-8 bytes: " + encodedBytes);
		}
		return value;
	}

	private static void writeIdentifier(RegistryFriendlyByteBuf buffer, Identifier identifier, int maxUtf8Bytes) {
		writeBoundedUtf8(buffer, identifier.toString(), maxUtf8Bytes, "Identifier");
	}

	private static Identifier readIdentifier(RegistryFriendlyByteBuf buffer, int maxUtf8Bytes) {
		return Identifier.parse(readBoundedUtf8(buffer, maxUtf8Bytes, "Identifier"));
	}
}
