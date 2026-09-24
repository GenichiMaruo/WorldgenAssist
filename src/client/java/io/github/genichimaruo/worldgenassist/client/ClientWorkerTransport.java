package io.github.genichimaruo.worldgenassist.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Loader-specific client play transport. */
public interface ClientWorkerTransport {
	boolean canSendHello();
	void send(CustomPacketPayload payload);
}
