package io.github.genichimaruo.worldgenassist.network;

import java.util.Objects;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;

public record WorkerAcceptedPayload(WorldgenProtocolVersion protocolVersion, Status status, int maxInFlightJobs)
	implements CustomPacketPayload {
	public static final Type<WorkerAcceptedPayload> TYPE = WorldgenPayloadTypes.type("worker_accepted");
	public static final StreamCodec<RegistryFriendlyByteBuf, WorkerAcceptedPayload> CODEC = CustomPacketPayload.codec(
		WorkerAcceptedPayload::write,
		WorkerAcceptedPayload::read
	);

	public WorkerAcceptedPayload {
		Objects.requireNonNull(protocolVersion, "protocolVersion");
		Objects.requireNonNull(status, "status");
		if (maxInFlightJobs < 0 || maxInFlightJobs > WorkerHelloPayload.MAX_PARALLEL_JOBS) {
			throw new IllegalArgumentException(
				"maxInFlightJobs must be between 0 and " + WorkerHelloPayload.MAX_PARALLEL_JOBS + ": " + maxInFlightJobs
			);
		}
		if ((status == Status.ACCEPTED) != (maxInFlightJobs > 0)) {
			throw new IllegalArgumentException("Only an accepted worker may have a positive in-flight limit");
		}
	}

	public boolean accepted() {
		return status == Status.ACCEPTED;
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(protocolVersion.value());
		buffer.writeByte(status.ordinal());
		buffer.writeVarInt(maxInFlightJobs);
	}

	private static WorkerAcceptedPayload read(RegistryFriendlyByteBuf buffer) {
		WorldgenProtocolVersion protocolVersion = new WorldgenProtocolVersion(buffer.readVarInt());
		int statusId = buffer.readUnsignedByte();
		if (statusId >= Status.values().length) {
			throw new IllegalArgumentException("Unknown worker acceptance status: " + statusId);
		}
		return new WorkerAcceptedPayload(protocolVersion, Status.values()[statusId], buffer.readVarInt());
	}

	@Override
	public Type<WorkerAcceptedPayload> type() {
		return TYPE;
	}

	public enum Status {
		ACCEPTED,
		REMOTE_DISABLED,
		UNSUPPORTED_PROTOCOL
	}
}
