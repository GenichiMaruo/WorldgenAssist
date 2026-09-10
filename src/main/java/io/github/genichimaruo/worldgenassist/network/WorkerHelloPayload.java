package io.github.genichimaruo.worldgenassist.network;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;

public record WorkerHelloPayload(WorldgenProtocolVersion protocolVersion, int maxParallelJobs, String implementationVersion)
	implements CustomPacketPayload {
	public static final int MAX_PARALLEL_JOBS = 64;
	public static final int MAX_IMPLEMENTATION_VERSION_UTF8_BYTES = 64;
	public static final Type<WorkerHelloPayload> TYPE = WorldgenPayloadTypes.type("worker_hello");
	public static final StreamCodec<RegistryFriendlyByteBuf, WorkerHelloPayload> CODEC = CustomPacketPayload.codec(
		WorkerHelloPayload::write,
		WorkerHelloPayload::read
	);

	public WorkerHelloPayload {
		Objects.requireNonNull(protocolVersion, "protocolVersion");
		Objects.requireNonNull(implementationVersion, "implementationVersion");
		if (maxParallelJobs < 1 || maxParallelJobs > MAX_PARALLEL_JOBS) {
			throw new IllegalArgumentException("maxParallelJobs must be between 1 and " + MAX_PARALLEL_JOBS + ": " + maxParallelJobs);
		}
		if (implementationVersion.isBlank()) {
			throw new IllegalArgumentException("implementationVersion must not be blank");
		}
		int implementationVersionBytes = implementationVersion.getBytes(StandardCharsets.UTF_8).length;
		if (implementationVersionBytes > MAX_IMPLEMENTATION_VERSION_UTF8_BYTES) {
			throw new IllegalArgumentException(
				"Implementation version exceeds " + MAX_IMPLEMENTATION_VERSION_UTF8_BYTES + " UTF-8 bytes: " + implementationVersionBytes
			);
		}
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(protocolVersion.value());
		buffer.writeVarInt(maxParallelJobs);
		WorldgenPayloadCodecs.writeBoundedUtf8(
			buffer,
			implementationVersion,
			MAX_IMPLEMENTATION_VERSION_UTF8_BYTES,
			"Implementation version"
		);
	}

	private static WorkerHelloPayload read(RegistryFriendlyByteBuf buffer) {
		return new WorkerHelloPayload(
			new WorldgenProtocolVersion(buffer.readVarInt()),
			buffer.readVarInt(),
			WorldgenPayloadCodecs.readBoundedUtf8(
				buffer,
				MAX_IMPLEMENTATION_VERSION_UTF8_BYTES,
				"Implementation version"
			)
		);
	}

	@Override
	public Type<WorkerHelloPayload> type() {
		return TYPE;
	}
}
