package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicPeerProbe263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@TempDir Path directory;
	private static TerrainDensityJob job(long seed, int x) {
		return new TerrainDensityJob(new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),Identifier.withDefaultNamespace("overworld"),x,3041,WorldgenContextFingerprint.fromBytes(new byte[32])),
			seed,true,Identifier.withDefaultNamespace("overworld"),0,16,1,1,TerrainWorkKind.COMPLETE_TERRAIN);
	}
	@Test void onlyExplicitPublicRegionIsCapturedWithExactInputsAndCountAndByteBounds() throws Exception {
		var chunks = new java.util.HashMap<net.minecraft.world.level.ChunkPos,net.minecraft.world.level.chunk.ChunkAccess>();
		for(int z=3040;z<=3042;z++) for(int x=-2013;x<=-2011;x++) {
			var pos=new net.minecraft.world.level.ChunkPos(x,z); chunks.put(pos,PrivateBiomeCache263Test.chunk(pos));
		}
		java.util.function.BiFunction<Integer,Integer,net.minecraft.world.level.chunk.ChunkAccess> window=(x,z)->chunks.get(new net.minecraft.world.level.ChunkPos(x,z));
		var data = new CompleteTerrainData(0,16,new byte[4096],new short[256],new short[256],new short[][]{new short[]{12,1,12}},TerrainBiomeWindow.digest(-2012,3041,0,16,window));
		Path output = directory.resolve("probe"); var probe = new PublicPeerProbe(true,output,16,512L*1024*1024);
		new PublicPeerProbe(false,output).capture(job(8675309,-2012),data,window);
		probe.capture(job(42,-2012),data,window); probe.capture(job(8675309,-1983),data,window);
		assertFalse(Files.exists(output));
		var first = job(8675309,-2012); probe.capture(first,data,window);
		assertEquals(data,CompleteTerrainData.decode(Files.readAllBytes(output.resolve(first.identity().jobId()+".bin")),0,16));
		var json = com.google.gson.JsonParser.parseString(Files.readString(output.resolve(first.identity().jobId()+".json"))).getAsJsonObject();
		assertEquals(first.identity().jobId().toString(),json.get("job_id").getAsString());
		assertEquals(8675309,json.get("public_seed").getAsLong()); assertEquals(-2012,json.get("x").getAsInt());
		assertArrayEquals(first.shaping().encode(),java.util.Base64.getDecoder().decode(json.get("shaping_base64").getAsString()));
		assertEquals("worldgen-assist.public-peer-probe.v2",json.get("schema").getAsString());
		assertEquals("minecraft:plains",json.getAsJsonArray("biome_names").get(0).getAsString());
		assertArrayEquals(new byte[9*16*4*2],java.util.Base64.getDecoder().decode(json.get("biome_samples_base64").getAsString()));
		assertEquals(java.util.HexFormat.of().formatHex(data.biomeWindowDigest()),json.get("biome_digest_hex").getAsString());
		long pairBytes=Files.size(output.resolve(first.identity().jobId()+".bin"))+Files.size(output.resolve(first.identity().jobId()+".json"));
		Path tooSmall=directory.resolve("too-small");
		new PublicPeerProbe(true,tooSmall,16,pairBytes-1).capture(first,data,window); assertFalse(Files.exists(tooSmall));
		for (int i = 0; i < 20; i++) probe.capture(job(8675309,-2012),data,window);
		try (var files = Files.list(output)) { assertEquals(32,files.count()); }
	}
}
