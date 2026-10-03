package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import io.github.genichimaruo.worldgenassist.common.*;

class GridDensity263Test {
	@BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
	@Test @Timeout(90) void sixContextsPreserveEveryGridAndFinalDensityBitAndFallback() {
		var lookup=VanillaRegistries.createWorldLookup();
		for(var key:List.of(NoiseGeneratorSettings.OVERWORLD,NoiseGeneratorSettings.AMPLIFIED,NoiseGeneratorSettings.LARGE_BIOMES)) {
			var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
			for(long seed:new long[]{8675309L,-987654321L}) {
				var client=RandomState.create(lookup.lookupOrThrow(Registries.NOISE),seed,settings);
				var server=RandomState.create(lookup.lookupOrThrow(Registries.NOISE),seed,settings);
				var noise=settings.noiseSettings();
				var id=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),Identifier.parse("minecraft:overworld"),-7,11,WorldgenContextFingerprint.fromBytes(new byte[32]));
				var job=new TerrainDensityJob(id,seed,true,key.identifier(),noise.minY(),noise.height(),1,1,TerrainWorkKind.GRID_AND_SURFACE);
				var result=TerrainDensityResultEnvelope.encode(GridDensityData.sample(job,client,settings)).decode();
				var reference=GridDensityData.sample(job,server,settings);
				assertEquals(6894,result.densityCount());
				for(int i=0;i<result.densityCount();i++)bits((float)reference.densityAt(i),(float)result.densityAt(i));
				assertEquals(8,RemoteDensityValidator.validate(job,result,8,server,settings,noise,new Random(2)).sampledValues());
				assertEquals(64,RemoteDensityValidator.validate(job,result,64,server,settings,noise,new Random(3)).sampledValues());
				var changed=result.densities();
				int group=RemoteDensityValidator.selectCells(job.sampleCount(),8,new Random(2))[0];changed[group]+=1;
				assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,()->RemoteDensityValidator.validate(job,new TerrainDensityResult(id,changed,0),8,server,settings,noise,new Random(2)));
				var field=new RemoteDensityField(job,result,settings,server);var target=new Target(field);
				var pool=server.acquireDensityBufferPool();
				try {
					var original=server.samplersWithContext(SamplerContext.builder().enableCaches().useBufferArena(pool).build());
					var gridVolume=GridDensityData.volume(job);var inputs=GridDensityData.inputs(settings);
					var aquifer=SurfaceDensityData.aquiferVolume(job);
					for(int z=0;z<aquifer.sizeZ();z++)for(int x=0;x<aquifer.sizeX();x++) {
						try(var point=original.get(settings.aquifers().orElseThrow().surfaceLevel()).sampleVolume(
							new DensityVolume(1,1,1,aquifer.blockX(x),0,aquifer.blockZ(z),4,1,4))) {
							bits(point.get(0),(float)result.densityAt(GridDensityData.surfaceOffset(job.height())+aquifer.indexUnchecked(x,0,z)));
						}
					}
					// Force both surface branches, rather than relying on a random draw covering them.
					for(int local:new int[]{0,26,512,513,530,768}) {
						int chosen=GridDensityData.surfaceOffset(job.height())+local;
						var selection=new Random(){@Override public int nextInt(int bound){assertEquals(job.sampleCount(),bound);return chosen;}};
						assertEquals(1,RemoteDensityValidator.validate(job,result,1,server,settings,noise,selection).sampledValues());
					}
					// A secret single-point bulk query must preserve every transported grid bit.
					for(int node=0;node<5;node++)for(int z=0;z<5;z++)for(int x=0;x<5;x++)for(int y=0;y<gridVolume.sizeY();y++) {
						try(var point=original.get(inputs.get(node)).sampleVolume(new DensityVolume(1,1,1,gridVolume.blockX(x),gridVolume.blockY(y),gridVolume.blockZ(z),4,8,4))) {
							bits(point.get(0),(float)result.densityAt(node*gridVolume.size()+gridVolume.indexUnchecked(x,y,z)));
						}
					}
					var volume=new DensityVolume(16,job.height(),16,-112,job.minY(),176);
					var remote=field.wrapSurfaceSamplers(server,settings,volume,original);
					assertSame(original,field.wrapSurfaceSamplers(client,settings,volume,original));
					var template=RemoteGridSamplers.template(server,settings);
					assertSame(template,RemoteGridSamplers.template(server,settings));
					RemoteDensitySamplingScope.run(target,()->{
						compare(original.get(settings.noiseRouter().finalDensity()),remote.get(settings.noiseRouter().finalDensity()),volume);
						// Partial/outside requests mix grid reads with the original input sampler.
						compare(original.get(settings.noiseRouter().finalDensity()),remote.get(settings.noiseRouter().finalDensity()),new DensityVolume(4,16,4,-114,job.minY(),174));
						return null;
					});
					assertTrue(field.remoteSamplesServed()>=6125);
					assertNull(RemoteDensitySamplingScope.current());
					compare(original.get(settings.noiseRouter().finalDensity()),remote.get(settings.noiseRouter().finalDensity()),volume);
				} finally{server.releaseDensityBufferPool(pool);}
			}
		}
	}
	@Test void typedGeometryAndCacheNeverCrossWorkKinds() {
		var dim=Identifier.parse("minecraft:overworld");var fp=WorldgenContextFingerprint.fromBytes(new byte[32]);var owner=UUID.randomUUID();
		var grid=new RemoteDensityResultCache.Key(1,dim,-7,11,fp,dim,-64,384,1,1,owner,1,TerrainWorkKind.GRID_AND_SURFACE);
		var surface=new RemoteDensityResultCache.Key(1,dim,-7,11,fp,dim,-64,384,1,1,owner,1,TerrainWorkKind.SURFACE_FIELDS);
		var id=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),dim,-7,11,fp);
		var cache=new RemoteDensityResultCache(2);var result=new TerrainDensityResult(id,new double[6894],0);
		cache.put(grid,result);assertTrue(cache.takeResult(surface).isEmpty());assertSame(result,cache.takeResult(grid).orElseThrow());
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityJob(id,0,true,dim,-63,384,1,1,TerrainWorkKind.GRID_AND_SURFACE));
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityJob(id,0,true,dim,-64,384,4,8,TerrainWorkKind.GRID_AND_SURFACE));
		assertEquals(6894,grid.sampleCount());assertThrows(IllegalArgumentException.class,()->TerrainWorkKind.fromWire(3));
	}
	private static void compare(DensitySampler.Bound a,DensitySampler.Bound b,DensityVolume volume) {
		try(var expected=a.sampleVolume(volume);var actual=b.sampleVolume(volume)) {for(int i=0;i<volume.size();i++)bits(expected.get(i),actual.get(i));}
	}
	private static void bits(float a,float b){assertEquals(Float.floatToRawIntBits(a),Float.floatToRawIntBits(b));}
	private static final class Target implements RemoteDensityTarget {
		RemoteDensityField field;Target(RemoteDensityField field){this.field=field;}
		@Override public void worldgenAssist$installRemoteDensity(RemoteDensityField field){this.field=field;}
		@Override public RemoteDensityField worldgenAssist$getRemoteDensity(){return field;}
		@Override public void worldgenAssist$clearRemoteDensity(UUID id){if(field!=null&&field.jobId().equals(id))field=null;}
		@Override public void worldgenAssist$installRemoteOpportunity(RemoteDensityOpportunity opportunity){}
		@Override public RemoteDensityOpportunity worldgenAssist$takeRemoteOpportunity(){return null;}
		@Override public void worldgenAssist$clearRemoteOpportunity(RemoteDensityOpportunity opportunity){}
	}
}
