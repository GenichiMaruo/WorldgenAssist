package io.github.genichimaruo.worldgenassist.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;

/** Bounded codec for the unregistered authenticated seeded-leaf job envelope. */
public final class AuthorizedSeededLeafJobCodec {
	public static final int MAX_ENCODED_BYTES = SeededLeafJobCodec.MAX_ENCODED_BYTES
		+ SeededLeafJobAuthenticationTag.BYTE_LENGTH + 1;
	public static final StreamCodec<RegistryFriendlyByteBuf, AuthorizedSeededLeafJob> CODEC = StreamCodec.of(
		AuthorizedSeededLeafJobCodec::encode,
		AuthorizedSeededLeafJobCodec::decode
	);

	private AuthorizedSeededLeafJobCodec() {
	}

	private static void encode(RegistryFriendlyByteBuf buffer, AuthorizedSeededLeafJob authorization) {
		buffer.writeByte(AuthorizedSeededLeafJob.FORMAT_VERSION);
		buffer.writeBytes(authorization.authenticationTag().bytes());
		SeededLeafJobCodec.CODEC.encode(buffer, authorization.job());
	}

	private static AuthorizedSeededLeafJob decode(RegistryFriendlyByteBuf buffer) {
		if (buffer.readableBytes() > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException(
				"Encoded authorized seeded-leaf job exceeds " + MAX_ENCODED_BYTES + " bytes: " + buffer.readableBytes()
			);
		}
		int formatVersion = buffer.readUnsignedByte();
		if (formatVersion != AuthorizedSeededLeafJob.FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported authorized seeded-leaf job format: " + formatVersion);
		}
		byte[] tag = new byte[SeededLeafJobAuthenticationTag.BYTE_LENGTH];
		buffer.readBytes(tag);
		return new AuthorizedSeededLeafJob(
			SeededLeafJobCodec.CODEC.decode(buffer),
			SeededLeafJobAuthenticationTag.fromBytes(tag)
		);
	}
}
