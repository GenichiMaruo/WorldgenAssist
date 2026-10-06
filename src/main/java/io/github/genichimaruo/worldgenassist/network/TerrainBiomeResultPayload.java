package io.github.genichimaruo.worldgenassist.network;

import java.util.Objects;
import io.github.genichimaruo.worldgenassist.common.CompleteBiomeData;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Optional first reply to the SAME complete assignment; it never completes that assignment. */
public record TerrainBiomeResultPayload(TerrainJobIdentity identity, CompleteBiomeData biomes) implements CustomPacketPayload {
	public static final Type<TerrainBiomeResultPayload> TYPE = WorldgenPayloadTypes.type("terrain_biome_result");
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainBiomeResultPayload> CODEC = CustomPacketPayload.codec(
		TerrainBiomeResultPayload::write, TerrainBiomeResultPayload::read);
	public TerrainBiomeResultPayload { Objects.requireNonNull(identity); Objects.requireNonNull(biomes); }
	private void write(RegistryFriendlyByteBuf buffer) {
		WorldgenPayloadCodecs.writeIdentity(buffer, identity);
		byte[] body = biomes.encode(); buffer.writeVarInt(body.length); buffer.writeBytes(body);
	}
	private static TerrainBiomeResultPayload read(RegistryFriendlyByteBuf buffer) {
		TerrainJobIdentity identity = WorldgenPayloadCodecs.readIdentity(buffer);
		int length = buffer.readVarInt();
		if (length < 14 || length > CompleteBiomeData.MAX_BYTES) throw new IllegalArgumentException("Invalid biome payload bound");
		byte[] body = new byte[length]; buffer.readBytes(body);
		return new TerrainBiomeResultPayload(identity, CompleteBiomeData.decode(body));
	}
	@Override public Type<TerrainBiomeResultPayload> type() { return TYPE; }
}
