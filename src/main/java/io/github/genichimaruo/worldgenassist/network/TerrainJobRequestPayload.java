package io.github.genichimaruo.worldgenassist.network;

import java.util.Objects;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;

public record TerrainJobRequestPayload(TerrainDensityJob job) implements CustomPacketPayload {
	public static final Type<TerrainJobRequestPayload> TYPE = WorldgenPayloadTypes.type("terrain_job_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainJobRequestPayload> CODEC = CustomPacketPayload.codec(
		TerrainJobRequestPayload::write,
		TerrainJobRequestPayload::read
	);

	public TerrainJobRequestPayload {
		Objects.requireNonNull(job, "job");
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		WorldgenPayloadCodecs.writeJob(buffer, job);
	}

	private static TerrainJobRequestPayload read(RegistryFriendlyByteBuf buffer) {
		return new TerrainJobRequestPayload(WorldgenPayloadCodecs.readJob(buffer));
	}

	@Override
	public Type<TerrainJobRequestPayload> type() {
		return TYPE;
	}
}
