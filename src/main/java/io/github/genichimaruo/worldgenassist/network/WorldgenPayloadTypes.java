package io.github.genichimaruo.worldgenassist.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;

public final class WorldgenPayloadTypes {
	public static final int MAX_DENSITY_RESULT_PAYLOAD_BYTES = TerrainDensityResultEnvelope.MAX_ENCODED_DENSITY_BYTES + 1_024;

	private WorldgenPayloadTypes() {
	}

	static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WorldgenAssist.MOD_ID, path));
	}
}
