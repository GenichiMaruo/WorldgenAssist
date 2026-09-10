package io.github.genichimaruo.worldgenassist.network;

import java.util.Objects;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafFixtureConfig;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;

/** Separate v3 public-fixture family; never silently upgrades the v2 protocol. */
public final class SeededLeafFixturePayloads {
	private static boolean registered;
	private SeededLeafFixturePayloads() { }
	public static synchronized void registerIfEnabled() {
		if (!SeededLeafFixtureConfig.payloadsEnabled() || registered) { return; }
		PayloadTypeRegistry.serverboundPlay().register(Hello.TYPE, Hello.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Accepted.TYPE, Accepted.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(Request.TYPE, Request.CODEC, AuthorizedSeededLeafJobCodec.MAX_ENCODED_BYTES);
		PayloadTypeRegistry.serverboundPlay().registerLarge(Result.TYPE, Result.CODEC, SeededLeafDensityResultEnvelopeCodec.MAX_ENCODED_BYTES);
		PayloadTypeRegistry.serverboundPlay().register(Failure.TYPE, Failure.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Cancel.TYPE, Cancel.CODEC);
		registered = true;
	}

	public record Hello(int protocol) implements CustomPacketPayload {
		public static final Type<Hello> TYPE = WorldgenPayloadTypes.type("seeded_leaf_fixture_v3_hello");
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of(
			(buffer, value) -> buffer.writeInt(value.protocol), buffer -> {
				if (buffer.readableBytes() != 4) { throw new IllegalArgumentException("Invalid fixture hello length"); }
				return new Hello(buffer.readInt());
			});
		@Override public Type<Hello> type() { return TYPE; }
	}
	public record Accepted(boolean enabled) implements CustomPacketPayload {
		public static final Type<Accepted> TYPE = WorldgenPayloadTypes.type("seeded_leaf_fixture_v3_accepted");
		public static final StreamCodec<RegistryFriendlyByteBuf, Accepted> CODEC = StreamCodec.of(
			(buffer, value) -> { buffer.writeInt(SeededLeafFixtureConfig.PROTOCOL); buffer.writeBoolean(value.enabled); }, buffer -> {
				if (buffer.readableBytes() != 5 || buffer.readInt() != SeededLeafFixtureConfig.PROTOCOL) {
					throw new IllegalArgumentException("Invalid fixture acceptance");
				}
				int enabled = buffer.readUnsignedByte();
				if (enabled > 1) { throw new IllegalArgumentException("Invalid fixture acceptance flag"); }
				return new Accepted(enabled == 1);
			});
		@Override public Type<Accepted> type() { return TYPE; }
	}
	public record Request(AuthorizedSeededLeafJob job) implements CustomPacketPayload {
		public static final Type<Request> TYPE = WorldgenPayloadTypes.type("seeded_leaf_fixture_v3_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = AuthorizedSeededLeafJobCodec.CODEC.map(Request::new, Request::job);
		public Request { Objects.requireNonNull(job, "job"); }
		@Override public Type<Request> type() { return TYPE; }
	}
	public record Result(SeededLeafDensityResultEnvelope result) implements CustomPacketPayload {
		public static final Type<Result> TYPE = WorldgenPayloadTypes.type("seeded_leaf_fixture_v3_result");
		public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = SeededLeafDensityResultEnvelopeCodec.CODEC.map(Result::new, Result::result);
		public Result { Objects.requireNonNull(result, "result"); }
		@Override public Type<Result> type() { return TYPE; }
	}
	public record Failure(SeededLeafJobClaim claim) implements CustomPacketPayload {
		public static final Type<Failure> TYPE = WorldgenPayloadTypes.type("seeded_leaf_fixture_v3_failure");
		public static final StreamCodec<RegistryFriendlyByteBuf, Failure> CODEC = SeededLeafJobClaimCodec.CODEC.map(Failure::new, Failure::claim);
		public Failure { Objects.requireNonNull(claim, "claim"); }
		@Override public Type<Failure> type() { return TYPE; }
	}
	public record Cancel(SeededLeafJobClaim claim) implements CustomPacketPayload {
		public static final Type<Cancel> TYPE = WorldgenPayloadTypes.type("seeded_leaf_fixture_v3_cancel");
		public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> CODEC = SeededLeafJobClaimCodec.CODEC.map(Cancel::new, Cancel::claim);
		public Cancel { Objects.requireNonNull(claim, "claim"); }
		@Override public Type<Cancel> type() { return TYPE; }
	}
}
