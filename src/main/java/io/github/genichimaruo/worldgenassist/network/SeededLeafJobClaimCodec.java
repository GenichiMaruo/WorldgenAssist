package io.github.genichimaruo.worldgenassist.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobAuthenticationTag;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;

/** Strict standalone codec for the unregistered seeded-leaf response claim. */
public final class SeededLeafJobClaimCodec {
	public static final int ENCODED_BYTES = 1 + Long.BYTES * 2
		+ OpaqueWorldgenContextId.BYTE_LENGTH
		+ SeededLeafJobAuthenticationTag.BYTE_LENGTH;
	public static final StreamCodec<RegistryFriendlyByteBuf, SeededLeafJobClaim> CODEC = StreamCodec.of(
		SeededLeafJobClaimCodec::encode,
		SeededLeafJobClaimCodec::decode
	);

	private SeededLeafJobClaimCodec() {
	}

	private static void encode(RegistryFriendlyByteBuf buffer, SeededLeafJobClaim claim) {
		encodeFields(buffer, claim);
	}

	static void encodeFields(RegistryFriendlyByteBuf buffer, SeededLeafJobClaim claim) {
		buffer.writeByte(SeededLeafJobClaim.FORMAT_VERSION);
		buffer.writeUUID(claim.jobId());
		buffer.writeBytes(claim.contextId().bytes());
		buffer.writeBytes(claim.authenticationTag().bytes());
	}

	private static SeededLeafJobClaim decode(RegistryFriendlyByteBuf buffer) {
		if (buffer.readableBytes() != ENCODED_BYTES) {
			throw new IllegalArgumentException(
				"Encoded seeded-leaf claim must contain exactly " + ENCODED_BYTES + " bytes: " + buffer.readableBytes()
			);
		}
		return decodeFields(buffer);
	}

	static SeededLeafJobClaim decodeFields(RegistryFriendlyByteBuf buffer) {
		int formatVersion = buffer.readUnsignedByte();
		if (formatVersion != SeededLeafJobClaim.FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported seeded-leaf claim format: " + formatVersion);
		}
		UUID jobId = buffer.readUUID();
		byte[] contextId = new byte[OpaqueWorldgenContextId.BYTE_LENGTH];
		buffer.readBytes(contextId);
		byte[] authenticationTag = new byte[SeededLeafJobAuthenticationTag.BYTE_LENGTH];
		buffer.readBytes(authenticationTag);
		return new SeededLeafJobClaim(
			jobId,
			OpaqueWorldgenContextId.fromBytes(contextId),
			SeededLeafJobAuthenticationTag.fromBytes(authenticationTag)
		);
	}
}
