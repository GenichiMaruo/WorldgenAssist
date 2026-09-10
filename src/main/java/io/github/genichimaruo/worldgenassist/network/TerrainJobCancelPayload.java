package io.github.genichimaruo.worldgenassist.network;

import java.util.Objects;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;

public record TerrainJobCancelPayload(TerrainJobIdentity identity) implements CustomPacketPayload {
	public static final Type<TerrainJobCancelPayload> TYPE = WorldgenPayloadTypes.type("terrain_job_cancel");
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainJobCancelPayload> CODEC = CustomPacketPayload.codec(
		TerrainJobCancelPayload::write,
		TerrainJobCancelPayload::read
	);

	public TerrainJobCancelPayload {
		Objects.requireNonNull(identity, "identity");
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		WorldgenPayloadCodecs.writeIdentity(buffer, identity);
	}

	private static TerrainJobCancelPayload read(RegistryFriendlyByteBuf buffer) {
		return new TerrainJobCancelPayload(WorldgenPayloadCodecs.readIdentity(buffer));
	}

	@Override
	public Type<TerrainJobCancelPayload> type() {
		return TYPE;
	}
}
