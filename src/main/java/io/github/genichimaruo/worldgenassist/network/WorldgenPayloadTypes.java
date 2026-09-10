package io.github.genichimaruo.worldgenassist.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;

public final class WorldgenPayloadTypes {
	public static final int MAX_DENSITY_RESULT_PAYLOAD_BYTES = TerrainDensityResultEnvelope.MAX_ENCODED_DENSITY_BYTES + 1_024;

	private static boolean registered;

	private WorldgenPayloadTypes() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		PayloadTypeRegistry.serverboundPlay().register(WorkerHelloPayload.TYPE, WorkerHelloPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WorkerAcceptedPayload.TYPE, WorkerAcceptedPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainJobRequestPayload.TYPE, TerrainJobRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().registerLarge(
			TerrainJobResultPayload.TYPE,
			TerrainJobResultPayload.CODEC,
			MAX_DENSITY_RESULT_PAYLOAD_BYTES
		);
		PayloadTypeRegistry.serverboundPlay().register(TerrainJobFailurePayload.TYPE, TerrainJobFailurePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainJobCancelPayload.TYPE, TerrainJobCancelPayload.CODEC);
		registered = true;
	}

	static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WorldgenAssist.MOD_ID, path));
	}
}
