package io.github.genichimaruo.worldgenassist.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test void disconnectDropsOnlyThatOwnersPartialAssembly() {
        List<ForgeResultFragmentPayload> parts = ForgeResultFragmentPayload.split(result());
        UUID disconnected = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        assertNull(ForgeResultAssembler.accept(disconnected, parts.getFirst()));
        assertNull(ForgeResultAssembler.accept(retained, parts.getFirst()));
        ForgeResultAssembler.removeOwner(disconnected);

        assertNull(ForgeResultAssembler.accept(disconnected, parts.get(1)));
        assertArrayEquals(resultBytes(parts),
            ForgeResultAssembler.accept(retained, parts.get(1)).encodedDensities());
        assertArrayEquals(resultBytes(parts),
            ForgeResultAssembler.accept(disconnected, parts.getFirst()).encodedDensities());
    }

    @Test void fullTerrainPackedAndFloatFragmentsRemainBoundedAndRoundTrip() {
        var identity=result().identity();
        for(var encoding:List.of(TerrainDensityResultEnvelope.Encoding.TERRAIN_CODES,TerrainDensityResultEnvelope.Encoding.RAW)) {
            byte[] raw=new byte[TerrainDensityResultEnvelope.rawBytes(99073,encoding)];
            var source=new TerrainDensityResultEnvelope(identity,99073,encoding,raw,100,200);
            var parts=ForgeResultFragmentPayload.split(source); UUID owner=UUID.randomUUID();
            TerrainDensityResultEnvelope complete=null;
            for(var part:parts.reversed()) {
                var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
                try {
                    ForgeResultFragmentPayload.CODEC.encode(buffer,part);
                    complete=ForgeResultAssembler.accept(owner,ForgeResultFragmentPayload.CODEC.decode(buffer));
                    assertEquals(0,buffer.readableBytes());
                } finally {buffer.release();}
            }
            assertEquals(source,complete); assertEquals(99073,complete.decode().densityCount());
            var first=parts.getFirst();
            assertThrows(IllegalArgumentException.class,()->new ForgeResultFragmentPayload(identity,99073,encoding,
                raw.length-1,100,200,0,parts.size(),first.bytes()));
        }
    }

    @Test void completeTerrainVariableRawFragmentsPreserveMetadata() {
        short[][] offsets=new short[24][];
        for(int i=0;i<offsets.length;i++)offsets[i]=new short[0];
        offsets[0]=new short[]{12,1,12};
        byte[][] biomePalettes=new byte[24][];java.util.Arrays.setAll(biomePalettes,i->new byte[]{0});
        var biomes=new io.github.genichimaruo.worldgenassist.common.CompleteBiomeData(-64,384,
            java.util.List.of("minecraft:plains"),biomePalettes,new byte[1536]);
        var data=new io.github.genichimaruo.worldgenassist.common.CompleteTerrainData(-64,384,new byte[98304],
            new short[256],new short[256],offsets,new byte[32],biomes);
        byte[] raw=data.encode();
        byte[] encoded=java.nio.ByteBuffer.allocate(raw.length+4).putInt(raw.length).put(raw).array();
        var source=new TerrainDensityResultEnvelope(result().identity(),98304,TerrainDensityResultEnvelope.Encoding.COMPLETE_TERRAIN,encoded,1,2);
        var parts=ForgeResultFragmentPayload.split(source);
        assertTrue(parts.size()>1);
        UUID owner=UUID.randomUUID();TerrainDensityResultEnvelope assembled=null;
        for(var part:parts.reversed()) assembled=ForgeResultAssembler.accept(owner,part);
        assertEquals(source,assembled);assertEquals(data,assembled.decode().completeTerrain());
        int bound=TerrainDensityResultEnvelope.rawBytes(98304,source.encoding());
        assertThrows(IllegalArgumentException.class,()->new ForgeResultFragmentPayload(source.identity(),98304,
            source.encoding(),bound+1,1,2,0,(bound+1+23999)/24000,new byte[24000]));
    }

    private static byte[] resultBytes(List<ForgeResultFragmentPayload> parts) {
        byte[] bytes = new byte[parts.getFirst().totalBytes()];
        for (ForgeResultFragmentPayload part : parts)
            System.arraycopy(part.bytes(), 0, bytes, part.partIndex() * ForgeResultFragmentPayload.MAX_PART_BYTES,
                part.bytes().length);
        return bytes;
    }

    private static TerrainDensityResultEnvelope result() {
        byte[] raw = new byte[32_000];
        for (int index = 0; index < raw.length; index++) raw[index] = (byte)(index * 31);
        TerrainJobIdentity identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(),
            Identifier.fromNamespaceAndPath("minecraft", "overworld"), 5, -7,
            WorldgenContextFingerprint.fromBytes(new byte[32]));
        return new TerrainDensityResultEnvelope(identity, 8_000, TerrainDensityResultEnvelope.Encoding.RAW,
            raw, 100, 200);
    }
}
