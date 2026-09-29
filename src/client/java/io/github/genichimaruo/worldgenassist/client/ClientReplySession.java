package io.github.genichimaruo.worldgenassist.client;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Revocation and submission are serialized; the sender captures one connection. */
final class ClientReplySession {
	private final Consumer<CustomPacketPayload> sender;
	private boolean active = true;
	ClientReplySession(Consumer<CustomPacketPayload> sender) { this.sender = java.util.Objects.requireNonNull(sender); }
	synchronized void close() { active = false; }
	synchronized boolean send(CustomPacketPayload payload, BooleanSupplier allowed) {
		if (!active || !allowed.getAsBoolean()) return false;
		sender.accept(payload);
		return true;
	}
}
