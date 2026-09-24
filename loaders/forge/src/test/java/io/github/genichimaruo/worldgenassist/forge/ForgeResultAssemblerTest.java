package io.github.genichimaruo.worldgenassist.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.ForgeResultFragmentPayload;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ForgeResultAssemblerTest {
    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    @AfterEach void clear() { ForgeResultAssembler.clear(); }

    @Test void reassemblesOutOfOrderAndDuplicateFragments() {
        TerrainDensityResultEnvelope source = result();
        List<ForgeResultFragmentPayload> parts = ForgeResultFragmentPayload.split(source);
        UUID owner = UUID.randomUUID();
        assertEquals(2, parts.size());
        assertEquals(24_000, parts.getFirst().bytes().length);
        assertNull(ForgeResultAssembler.accept(owner, parts.get(1)));
        assertNull(ForgeResultAssembler.accept(owner, parts.get(1)));
        TerrainDensityResultEnvelope assembled = ForgeResultAssembler.accept(owner, parts.getFirst());
        assertEquals(source.identity(), assembled.identity());
        assertArrayEquals(source.encodedDensities(), assembled.encodedDensities());
    }

    @Test void ownerAndMetadataMismatchCannotCompleteAnAssembly() {
        TerrainDensityResultEnvelope source = result();
        List<ForgeResultFragmentPayload> parts = ForgeResultFragmentPayload.split(source);
        UUID owner = UUID.randomUUID();
        assertNull(ForgeResultAssembler.accept(owner, parts.getFirst()));
        assertNull(ForgeResultAssembler.accept(UUID.randomUUID(), parts.get(1)));
        ForgeResultFragmentPayload second = parts.get(1);
        ForgeResultFragmentPayload changed = new ForgeResultFragmentPayload(second.identity(), second.densityCount(),
            second.encoding(), second.totalBytes(), second.clientComputeNanos() + 1,
            second.clientEncodeNanos(), second.partIndex(), second.partCount(), second.bytes());
        assertNull(ForgeResultAssembler.accept(owner, changed));
        assertNull(ForgeResultAssembler.accept(owner, second));
        assertArrayEquals(source.encodedDensities(),
            ForgeResultAssembler.accept(owner, parts.getFirst()).encodedDensities());
    }

    private static TerrainDensityResultEnvelope result() {
        byte[] raw = new byte[32_000];
        for (int index = 0; index < raw.length; index++) raw[index] = (byte)(index * 31);
        TerrainJobIdentity identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(),
            Identifier.fromNamespaceAndPath("minecraft", "overworld"), 5, -7,
            WorldgenContextFingerprint.fromBytes(new byte[32]));
        return new TerrainDensityResultEnvelope(identity, 4_000, TerrainDensityResultEnvelope.Encoding.RAW,
            raw, 100, 200);
    }
}
