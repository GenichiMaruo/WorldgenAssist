package io.github.genichimaruo.worldgenassist.network;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A bounded piece of a density result for Forge's 32,767-byte serverbound payload limit. */
public record ForgeResultFragmentPayload(
    TerrainJobIdentity identity,
    int densityCount,
    TerrainDensityResultEnvelope.Encoding encoding,
    int totalBytes,
    long clientComputeNanos,
    long clientEncodeNanos,
    int partIndex,
    int partCount,
    byte[] bytes
) implements CustomPacketPayload {
    public static final int MAX_PART_BYTES = 24_000;
    public static final Type<ForgeResultFragmentPayload> TYPE = WorldgenPayloadTypes.type("forge_result_fragment");
    public static final StreamCodec<RegistryFriendlyByteBuf, ForgeResultFragmentPayload> CODEC =
        CustomPacketPayload.codec(ForgeResultFragmentPayload::write, ForgeResultFragmentPayload::read);

    public ForgeResultFragmentPayload {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(bytes, "bytes");
        if (densityCount < 1 || densityCount > TerrainDensityJob.MAX_SAMPLE_COUNT)
            throw new IllegalArgumentException("Invalid density count: " + densityCount);
        int rawBytes = Math.multiplyExact(densityCount, TerrainDensityResultEnvelope.BYTES_PER_DENSITY);
        if (totalBytes < 1 || totalBytes > rawBytes || encoding == TerrainDensityResultEnvelope.Encoding.RAW && totalBytes != rawBytes)
            throw new IllegalArgumentException("Invalid encoded result length: " + totalBytes);
        int expectedParts = (totalBytes + MAX_PART_BYTES - 1) / MAX_PART_BYTES;
        if (partCount != expectedParts || partIndex < 0 || partIndex >= partCount)
            throw new IllegalArgumentException("Invalid result fragment index/count");
        int expectedBytes = Math.min(MAX_PART_BYTES, totalBytes - partIndex * MAX_PART_BYTES);
        if (bytes.length != expectedBytes) throw new IllegalArgumentException("Invalid result fragment length");
        if (clientComputeNanos < 0 || clientComputeNanos > io.github.genichimaruo.worldgenassist.common.TerrainDensityResult.MAX_CLIENT_COMPUTE_NANOS
            || clientEncodeNanos < 0 || clientEncodeNanos > io.github.genichimaruo.worldgenassist.common.TerrainDensityResult.MAX_CLIENT_COMPUTE_NANOS)
            throw new IllegalArgumentException("Invalid client timing");
        bytes = bytes.clone();
    }

    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public Type<ForgeResultFragmentPayload> type() { return TYPE; }

    public static List<ForgeResultFragmentPayload> split(TerrainDensityResultEnvelope result) {
        byte[] encoded = result.encodedDensities();
        int partCount = (encoded.length + MAX_PART_BYTES - 1) / MAX_PART_BYTES;
        List<ForgeResultFragmentPayload> fragments = new ArrayList<>(partCount);
        for (int index = 0; index < partCount; index++) {
            int start = index * MAX_PART_BYTES;
            fragments.add(new ForgeResultFragmentPayload(result.identity(), result.densityCount(), result.encoding(),
                encoded.length, result.clientComputeNanos(), result.clientEncodeNanos(), index, partCount,
                Arrays.copyOfRange(encoded, start, Math.min(encoded.length, start + MAX_PART_BYTES))));
        }
        return fragments;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        WorldgenPayloadCodecs.writeIdentity(buffer, identity);
        buffer.writeVarInt(densityCount);
        buffer.writeByte(encoding.ordinal());
        buffer.writeVarInt(totalBytes);
        buffer.writeVarLong(clientComputeNanos);
        buffer.writeVarLong(clientEncodeNanos);
        buffer.writeVarInt(partIndex);
        buffer.writeVarInt(partCount);
        buffer.writeVarInt(bytes.length);
        buffer.writeBytes(bytes);
    }

    private static ForgeResultFragmentPayload read(RegistryFriendlyByteBuf buffer) {
        TerrainJobIdentity identity = WorldgenPayloadCodecs.readIdentity(buffer);
        int densityCount = buffer.readVarInt();
        int encodingId = buffer.readUnsignedByte();
        if (encodingId >= TerrainDensityResultEnvelope.Encoding.values().length)
            throw new IllegalArgumentException("Unknown result encoding: " + encodingId);
        int totalBytes = buffer.readVarInt();
        long computeNanos = buffer.readVarLong();
        long encodeNanos = buffer.readVarLong();
        int partIndex = buffer.readVarInt();
        int partCount = buffer.readVarInt();
        int length = buffer.readVarInt();
        if (length < 1 || length > MAX_PART_BYTES) throw new IllegalArgumentException("Invalid result fragment length: " + length);
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return new ForgeResultFragmentPayload(identity, densityCount,
            TerrainDensityResultEnvelope.Encoding.values()[encodingId], totalBytes,
            computeNanos, encodeNanos, partIndex, partCount, bytes);
    }
}
