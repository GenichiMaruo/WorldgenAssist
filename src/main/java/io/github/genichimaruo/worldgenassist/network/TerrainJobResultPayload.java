package io.github.genichimaruo.worldgenassist.network;

import java.util.Objects;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;

public record TerrainJobResultPayload(TerrainDensityResultEnvelope result) implements CustomPacketPayload {
	public static final Type<TerrainJobResultPayload> TYPE = WorldgenPayloadTypes.type("terrain_job_result");
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainJobResultPayload> CODEC = CustomPacketPayload.codec(
		TerrainJobResultPayload::write,
		TerrainJobResultPayload::read
	);

	public TerrainJobResultPayload {
		Objects.requireNonNull(result, "result");
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		WorldgenPayloadCodecs.writeResult(buffer, result);
	}

	private static TerrainJobResultPayload read(RegistryFriendlyByteBuf buffer) {
		return new TerrainJobResultPayload(WorldgenPayloadCodecs.readResult(buffer));
	}

	@Override
	public Type<TerrainJobResultPayload> type() {
		return TYPE;
	}
}
