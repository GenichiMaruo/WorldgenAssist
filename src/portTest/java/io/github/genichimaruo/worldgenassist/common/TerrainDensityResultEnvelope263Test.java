package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class TerrainDensityResultEnvelope263Test {
	// Identity validation touches ChunkStatus registries: independent of test order.
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static final TerrainJobIdentity IDENTITY = new TerrainJobIdentity(
		WorldgenProtocolVersion.CURRENT, UUID.fromString("d39ecf82-123f-4e61-8c34-91368331336e"),
		Identifier.parse("example:skylands"), 4, -7,
		WorldgenContextFingerprint.fromBytes(new byte[WorldgenContextFingerprint.BYTE_LENGTH])
	);

	@Test
	void transportsExactFloatBitsAndUsesFourBytesPerSample() {
		double[] samples = {0.0, -0.0, 1.25, -1234.5, Float.MIN_VALUE, 1_000_000.0};
		TerrainDensityResultEnvelope envelope = TerrainDensityResultEnvelope.encode(
			new TerrainDensityResult(IDENTITY, samples, 10)
		);
		assertEquals(Float.BYTES * samples.length, envelope.rawDensityBytes());
		TerrainDensityResult decoded = envelope.decode();
		for (int index = 0; index < samples.length; index++) {
			assertEquals(Double.doubleToRawLongBits(samples[index]),
				Double.doubleToRawLongBits(decoded.densityAt(index)), "sample " + index);
		}
	}

	@Test
	void refusesAValueThatWouldChangeWhenConvertedToFloat() {
		assertThrows(IllegalArgumentException.class, () -> TerrainDensityResultEnvelope.encode(
			new TerrainDensityResult(IDENTITY, new double[] {1.0 + Math.ulp(1.0)}, 10)
		));
	}

	@Test void floatStorageAndPackedCodesPreserveIdentityBitsAndOwnership() {
		float[] values = new float[99073];
		for (int i = 0; i < 98304; i++) values[i] = i % 7;
		values[98304] = -0.0f; values[98305] = Float.MIN_VALUE; values[98306] = -1234.5f;
		var input = TerrainDensityResult.fromFloats(IDENTITY, values, 10);
		values[0] = 6; // Constructor owns a private copy.
		var doubles = input.densities(); doubles[0] = 6;
		assertEquals(0, input.densityAt(0));
		var expanded = new TerrainDensityResult(IDENTITY,input.densities(),10);
		assertEquals(expanded,input); assertEquals(expanded.hashCode(),input.hashCode());
		var envelope = TerrainDensityResultEnvelope.encode(input,TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE);
		var compact = envelope.decode();
		assertTrue(compact.hasTerrainCodes(98304)); assertFalse(compact.hasTerrainCodes(98303));
		byte[] ownedCodes = compact.terrainCodes(); float[] ownedSurface = compact.terrainSurface();
		var rebuilt = TerrainDensityResult.fromTerrainCodes(IDENTITY,ownedCodes,ownedSurface,10);
		ownedCodes[0] = 6; ownedSurface[0] = 123;
		rebuilt.terrainCodes()[0] = 6; rebuilt.terrainSurface()[0] = 123;
		assertEquals(input,rebuilt); assertEquals(input.hashCode(),rebuilt.hashCode());
		assertEquals(input,TerrainDensityResultEnvelope.encode(rebuilt,TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE).decode());
		assertEquals(input,TerrainDensityResultEnvelope.encode(rebuilt).decode());
		assertTrue(envelope.encoding().decisions()); assertEquals(101380,envelope.rawDensityBytes());
		var payload = new io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload(envelope);
		var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
		try {
			io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload.CODEC.encode(buffer,payload);
			var received=io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload.CODEC.decode(buffer).result();
			assertEquals(envelope,received); assertEquals(input,received.decode()); assertEquals(0,buffer.readableBytes());
		} finally {buffer.release();}
		var raw = new byte[101380];
		var packed = java.nio.ByteBuffer.wrap(raw);
		for (int i=0;i<98304;i++) packed.put((byte)(int)input.densityAt(i));
		for (int i=98304;i<99073;i++) packed.putInt(Float.floatToRawIntBits((float)input.densityAt(i)));
		assertEquals(input,new TerrainDensityResultEnvelope(IDENTITY,99073,TerrainDensityResultEnvelope.Encoding.TERRAIN_CODES,raw,10,0).decode());
	}

	@Test void packedDomainGeometryAndCompressionRemainBounded() {
		var kind=TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE;
		var encoding=TerrainDensityResultEnvelope.Encoding.TERRAIN_CODES;
		assertThrows(IllegalArgumentException.class,()->TerrainDensityResult.fromTerrainCodes(IDENTITY,new byte[98303],new float[769],0));
		assertThrows(IllegalArgumentException.class,()->TerrainDensityResult.fromTerrainCodes(IDENTITY,new byte[100352],new float[769],0));
		assertThrows(IllegalArgumentException.class,()->TerrainDensityResult.fromTerrainCodes(IDENTITY,new byte[98304],new float[768],0));
		assertThrows(IllegalArgumentException.class,()->TerrainDensityResult.fromTerrainCodes(IDENTITY,new byte[98304],new float[769],-1));
		for(byte invalid:new byte[]{7,-1}) {
			byte[] codes=new byte[98304]; codes[0]=invalid;
			assertThrows(IllegalArgumentException.class,()->TerrainDensityResult.fromTerrainCodes(IDENTITY,codes,new float[769],0));
		}
		for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,1_000_001f}) {
			float[] surface=new float[769]; surface[0]=invalid;
			assertThrows(IllegalArgumentException.class,()->TerrainDensityResult.fromTerrainCodes(IDENTITY,new byte[98304],surface,0));
		}
		assertThrows(IllegalArgumentException.class,()->TerrainDensityResultEnvelope.rawBytes(98304,encoding));
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityResultEnvelope(IDENTITY,99073,encoding,new byte[101379],0,0));
		float[] values=new float[99073];
		for(float invalid:new float[]{7,0.5f,-0.0f}) {
			values[0]=invalid;
			assertThrows(IllegalArgumentException.class,()->TerrainDensityResultEnvelope.encode(TerrainDensityResult.fromFloats(IDENTITY,values,0),kind));
		}
		values[0]=0;
		var good=TerrainDensityResultEnvelope.encode(TerrainDensityResult.fromFloats(IDENTITY,values,0),kind);
		assertTrue(good.encoding().compressed());
		for(int length:new int[]{good.encodedDensityBytes()-1,good.encodedDensityBytes()+1}) {
			byte[] changed=java.util.Arrays.copyOf(good.encodedDensities(),length);
			assertThrows(IllegalArgumentException.class,()->new TerrainDensityResultEnvelope(IDENTITY,99073,good.encoding(),changed,0,0).decode());
		}
		byte[] badCode=new byte[101380]; badCode[0]=7;
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityResultEnvelope(IDENTITY,99073,encoding,badCode,0,0).decode());
		var deflater=new java.util.zip.Deflater();byte[] oversize=new byte[101381];byte[] output=new byte[101380];
		try {
			deflater.setInput(oversize);deflater.finish();int length=deflater.deflate(output);
			byte[] encoded=java.util.Arrays.copyOf(output,length);
			assertThrows(IllegalArgumentException.class,()->new TerrainDensityResultEnvelope(IDENTITY,99073,TerrainDensityResultEnvelope.Encoding.DEFLATE_TERRAIN_CODES,encoded,0,0).decode());
		} finally {deflater.end();}
	}
}
