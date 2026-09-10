package io.github.genichimaruo.worldgenassist.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;

/** Bounded codec for the unregistered seeded-leaf job draft. */
public final class SeededLeafJobCodec {
	public static final int MAX_ENCODED_BYTES = SeededLeafTranscriptCodec.MAX_ENCODED_BYTES + 1_000;
	public static final StreamCodec<RegistryFriendlyByteBuf, SeededLeafJob> CODEC = StreamCodec.of(
		SeededLeafJobCodec::encode,
		SeededLeafJobCodec::decode
	);

	private SeededLeafJobCodec() {
	}

	private static void encode(RegistryFriendlyByteBuf buffer, SeededLeafJob job) {
		buffer.writeByte(SeededLeafJob.FORMAT_VERSION);
		WorldgenPayloadCodecs.writeBoundedUtf8(
			buffer,
			SeededLeafJob.DENSITY_GRAPH_ID,
			SeededLeafJob.MAX_IDENTIFIER_UTF8_BYTES,
			"Density graph ID"
		);
		buffer.writeUUID(job.jobId());
		buffer.writeBytes(job.contextId().bytes());
		writeIdentifier(buffer, job.dimension());
		buffer.writeInt(job.chunkX());
		buffer.writeInt(job.chunkZ());
		writeIdentifier(buffer, job.noiseSettings());
		buffer.writeInt(job.minY());
		buffer.writeVarInt(job.height());
		buffer.writeVarInt(job.cellWidth());
		buffer.writeVarInt(job.cellHeight());
		SeededLeafTranscriptCodec.CODEC.encode(buffer, job.transcript());
	}

	private static SeededLeafJob decode(RegistryFriendlyByteBuf buffer) {
		if (buffer.readableBytes() > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException(
				"Encoded seeded-leaf job exceeds " + MAX_ENCODED_BYTES + " bytes: " + buffer.readableBytes()
			);
		}
		int formatVersion = buffer.readUnsignedByte();
		if (formatVersion != SeededLeafJob.FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported seeded-leaf job format: " + formatVersion);
		}
		String graphId = WorldgenPayloadCodecs.readBoundedUtf8(
			buffer,
			SeededLeafJob.MAX_IDENTIFIER_UTF8_BYTES,
			"Density graph ID"
		);
		if (!SeededLeafJob.DENSITY_GRAPH_ID.equals(graphId)) {
			throw new IllegalArgumentException("Unsupported seeded-leaf density graph: " + graphId);
		}
		java.util.UUID jobId = buffer.readUUID();
		byte[] contextId = new byte[OpaqueWorldgenContextId.BYTE_LENGTH];
		buffer.readBytes(contextId);
		Identifier dimension = readIdentifier(buffer);
		int chunkX = buffer.readInt();
		int chunkZ = buffer.readInt();
		Identifier noiseSettings = readIdentifier(buffer);
		int minY = buffer.readInt();
		int height = buffer.readVarInt();
		int cellWidth = buffer.readVarInt();
		int cellHeight = buffer.readVarInt();
		return new SeededLeafJob(
			jobId,
			OpaqueWorldgenContextId.fromBytes(contextId),
			dimension,
			chunkX,
			chunkZ,
			noiseSettings,
			minY,
			height,
			cellWidth,
			cellHeight,
			SeededLeafTranscriptCodec.CODEC.decode(buffer)
		);
	}

	private static void writeIdentifier(RegistryFriendlyByteBuf buffer, Identifier identifier) {
		WorldgenPayloadCodecs.writeBoundedUtf8(
			buffer,
			identifier.toString(),
			SeededLeafJob.MAX_IDENTIFIER_UTF8_BYTES,
			"Identifier"
		);
	}

	private static Identifier readIdentifier(RegistryFriendlyByteBuf buffer) {
		return Identifier.parse(WorldgenPayloadCodecs.readBoundedUtf8(
			buffer,
			SeededLeafJob.MAX_IDENTIFIER_UTF8_BYTES,
			"Identifier"
		));
	}
}
