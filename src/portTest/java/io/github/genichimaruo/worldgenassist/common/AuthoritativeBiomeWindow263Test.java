package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

class AuthoritativeBiomeWindow263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void originalWindowSurvivesWireAndPrivateRestorationWithoutSharingMutableChunks() {
		var source=new HashMap<ChunkPos,ProtoChunk>();
		for(int z=2;z<=4;z++) for(int x=-3;x<=-1;x++) {
			var pos=new ChunkPos(x,z);var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),PrivateBiomeCache263Test.CONTAINERS,null);
			chunk.fillBiomesFromNoise((qx,qy,qz)->Math.floorMod(qx,2)==0 ? PrivateBiomeCache263Test.FOREST
				: Math.floorMod(qy,3)==0 ? PrivateBiomeCache263Test.DESERT : PrivateBiomeCache263Test.PLAINS);
			chunk.setPersistedStatus(ChunkStatus.BIOMES);source.put(pos,chunk);
		}
		var input=AuthoritativeBiomeWindow.capture(-2,3,-64,384,(x,z)->source.get(new ChunkPos(x,z)));
		assertArrayEquals(TerrainBiomeWindow.digest(-2,3,-64,384,(x,z)->source.get(new ChunkPos(x,z))),input.digest());
		var decoded=AuthoritativeBiomeWindow.decode(input.encode());assertEquals(input,decoded);
		assertThrows(UnsupportedOperationException.class,()->decoded.chunks().clear());
		var possible=Set.of(PrivateBiomeCache263Test.PLAINS,PrivateBiomeCache263Test.FOREST,PrivateBiomeCache263Test.DESERT);
		var restored=decoded.restore(PrivateBiomeCache263Test.LOOKUP,PrivateBiomeCache263Test.CONTAINERS,possible);
		for(int z=0;z<3;z++) for(int x=0;x<3;x++) {
			assertNotSame(source.get(restored[z][x].getPos()),restored[z][x]);
			assertEquals(decoded.chunks().get(z*3+x),CompleteBiomeData.capture(restored[z][x]));
			for(var section:restored[z][x].getSections()) assertTrue(section.hasOnlyAir());
		}
		assertArrayEquals(input.digest(),TerrainBiomeWindow.digest(-2,3,-64,384,(x,z)->restored[z-2][x+3]));
		var before=input.encode();source.get(new ChunkPos(-2,3)).fillBiomesFromNoise((x,y,z)->PrivateBiomeCache263Test.DESERT);
		restored[1][1].fillBiomesFromNoise((x,y,z)->PrivateBiomeCache263Test.FOREST);
		assertArrayEquals(before,input.encode());assertEquals(decoded.chunks().get(0),CompleteBiomeData.capture(restored[0][0]));
		assertThrows(IllegalArgumentException.class,()->decoded.restore(PrivateBiomeCache263Test.LOOKUP,PrivateBiomeCache263Test.CONTAINERS,Set.of(PrivateBiomeCache263Test.PLAINS)));
		var wrong=source.get(new ChunkPos(-3,2));wrong.setPersistedStatus(ChunkStatus.EMPTY);
		assertThrows(IllegalArgumentException.class,()->AuthoritativeBiomeWindow.capture(-2,3,-64,384,(x,z)->source.get(new ChunkPos(x,z))));
		assertThrows(IllegalArgumentException.class,()->AuthoritativeBiomeWindow.decode(Arrays.copyOf(before,before.length-1)));
		assertThrows(IllegalArgumentException.class,()->AuthoritativeBiomeWindow.decode(Arrays.copyOf(before,before.length+1)));
		assertThrows(IllegalArgumentException.class,()->AuthoritativeBiomeWindow.decode(new byte[AuthoritativeBiomeWindow.MAX_BYTES+1]));
		byte[] malformed=before.clone();java.nio.ByteBuffer.wrap(malformed).putInt(20,CompleteBiomeData.MAX_BYTES+1);
		assertThrows(IllegalArgumentException.class,()->AuthoritativeBiomeWindow.decode(malformed));
		assertThrows(IllegalArgumentException.class,()->new AuthoritativeBiomeWindow(-2,3,0,384,input.chunks()));
		var dim=net.minecraft.resources.Identifier.withDefaultNamespace("overworld");
		var identity=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),dim,-2,4,WorldgenContextFingerprint.fromBytes(new byte[32]));
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityJob(identity,1,true,dim,-64,384,1,1,TerrainWorkKind.COMPLETE_TERRAIN,TerrainBeardifierData.EMPTY,false,input));
	}
}
