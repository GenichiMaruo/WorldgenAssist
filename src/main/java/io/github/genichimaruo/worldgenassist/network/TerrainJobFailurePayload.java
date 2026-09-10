package io.github.genichimaruo.worldgenassist.network;

import java.util.Objects;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;

public record TerrainJobFailurePayload(TerrainJobIdentity identity, Reason reason) implements CustomPacketPayload {
	public static final Type<TerrainJobFailurePayload> TYPE = WorldgenPayloadTypes.type("terrain_job_failure");
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainJobFailurePayload> CODEC = CustomPacketPayload.codec(
		TerrainJobFailurePayload::write,
		TerrainJobFailurePayload::read
	);

	public TerrainJobFailurePayload {
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(reason, "reason");
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		WorldgenPayloadCodecs.writeIdentity(buffer, identity);
		buffer.writeByte(reason.ordinal());
	}

	private static TerrainJobFailurePayload read(RegistryFriendlyByteBuf buffer) {
		TerrainJobIdentity identity = WorldgenPayloadCodecs.readIdentity(buffer);
		int reasonId = buffer.readUnsignedByte();
		if (reasonId >= Reason.values().length) {
			throw new IllegalArgumentException("Unknown terrain job failure reason: " + reasonId);
		}
		return new TerrainJobFailurePayload(identity, Reason.values()[reasonId]);
	}

	@Override
	public Type<TerrainJobFailurePayload> type() {
		return TYPE;
	}

	public enum Reason {
		BUSY,
		UNSUPPORTED_CONTEXT,
		CONTEXT_MISMATCH,
		COMPUTE_FAILED
	}
}
