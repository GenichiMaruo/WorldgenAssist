package io.github.genichimaruo.worldgenassist.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Fabric's packet registry; the payload types and codecs are loader-neutral. */
public final class FabricPayloadRegistration {
	private static boolean registered;

	private FabricPayloadRegistration() {}

	public static synchronized void register() {
		if (registered) return;
		PayloadTypeRegistry.serverboundPlay().register(WorkerHelloPayload.TYPE, WorkerHelloPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WorkerAcceptedPayload.TYPE, WorkerAcceptedPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainJobRequestPayload.TYPE, TerrainJobRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().registerLarge(TerrainJobResultPayload.TYPE,
			TerrainJobResultPayload.CODEC, WorldgenPayloadTypes.MAX_DENSITY_RESULT_PAYLOAD_BYTES);
		PayloadTypeRegistry.serverboundPlay().register(TerrainJobFailurePayload.TYPE, TerrainJobFailurePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainJobCancelPayload.TYPE, TerrainJobCancelPayload.CODEC);
		registered = true;
	}
}
