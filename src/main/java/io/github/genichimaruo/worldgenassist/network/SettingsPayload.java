package io.github.genichimaruo.worldgenassist.network;

import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenConfig;
import java.time.Duration;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Separate v1 settings channel; does not change the density protocol CURRENT. */
public record SettingsPayload(int action, int revision, long requestId, RemoteWorldgenConfig config) implements CustomPacketPayload {
	public static final int READ = 0, SAVE = 1, STATE = 2, SAVED = 3, DENIED = 4, STALE = 5, IO_ERROR = 6, RATE_LIMITED = 7;
	public static final Type<SettingsPayload> TYPE = WorldgenPayloadTypes.type("settings_v1");
	public static final StreamCodec<RegistryFriendlyByteBuf, SettingsPayload> CODEC = CustomPacketPayload.codec(SettingsPayload::write, SettingsPayload::read);
	public SettingsPayload {
		if (action < READ || action > RATE_LIMITED || revision < 0 || requestId < 0L) throw new IllegalArgumentException("Invalid settings action, revision, or request ID");
		Objects.requireNonNull(config);
	}
	private void write(RegistryFriendlyByteBuf b) {
		b.writeVarInt(action); b.writeVarInt(revision); b.writeVarLong(requestId);
		b.writeBoolean(config.enabled()); b.writeBoolean(config.seedDisclosureMode() == RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW);
		b.writeVarInt(config.maxInFlightJobs()); b.writeVarLong(config.jobTimeout().toMillis());
		b.writeVarInt(config.cacheEntries()); b.writeBoolean(config.predictionEnabled());
		b.writeVarInt(config.predictionIntervalTicks()); b.writeVarInt(config.predictionLeadChunks()); b.writeVarInt(config.validationSampleCells());
	}
	private static SettingsPayload read(RegistryFriendlyByteBuf b) {
		return new SettingsPayload(b.readVarInt(), b.readVarInt(), b.readVarLong(), new RemoteWorldgenConfig(b.readBoolean(),
			b.readBoolean() ? RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW : RemoteWorldgenConfig.SeedDisclosureMode.DENY,
			b.readVarInt(), Duration.ofMillis(b.readVarLong()), b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
	}
	@Override public Type<SettingsPayload> type() { return TYPE; }
}
