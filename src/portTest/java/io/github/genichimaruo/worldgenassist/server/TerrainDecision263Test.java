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
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.*;
import io.github.genichimaruo.worldgenassist.common.*;

class TerrainDecision263Test {
	@BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
	@Test @Timeout(120) void fillDecisionsAndAlignedValidationPreserveVanilla() {
		var lookup=VanillaRegistries.createWorldLookup();
		for(var key:List.of(NoiseGeneratorSettings.OVERWORLD,NoiseGeneratorSettings.AMPLIFIED,NoiseGeneratorSettings.LARGE_BIOMES)) {
			var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
			for(long seed:key==NoiseGeneratorSettings.OVERWORLD?new long[]{8675309L,-987654321L}:new long[]{8675309L}) {
				var client=RandomState.create(lookup.lookupOrThrow(Registries.NOISE),seed,settings);
				var server=RandomState.create(lookup.lookupOrThrow(Registries.NOISE),seed,settings);
				var validator=RandomState.create(lookup.lookupOrThrow(Registries.NOISE),seed,settings);
				var noise=settings.noiseSettings();
				var id=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),Identifier.parse("minecraft:overworld"),-7,11,WorldgenContextFingerprint.fromBytes(new byte[32]));
				var job=new TerrainDensityJob(id,seed,true,key.identifier(),noise.minY(),noise.height(),1,1,TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE);
				var envelope=TerrainDensityResultEnvelope.encode(TerrainDecisionData.sample(job,client,settings),job.workKind());
				var result=envelope.decode();var field=new RemoteDensityField(job,result,settings,server);
				var full=BlockDensityData.volume(job);
				assertThrows(IllegalArgumentException.class,()->field.requireDecisionFill(full,settings));
				try(var chunk=new NoiseChunk(server,Beardifier.EMPTY,settings,TerrainDecisionData.fluidPicker(settings),Blender.empty(),full);
					var reference=chunk.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(full);
					var codes=SamplerContext.EMPTY_UNCACHED.acquireBuffer(full)) {
					field.copyVolume(full,codes);
					var aquifer=chunk.aquifer();
					for(int z=0;z<16;z++) for(int x=0;x<16;x++) for(int y=job.height()-1;y>=0;y--) {
						int bx=full.blockX(x),by=full.blockY(y),bz=full.blockZ(z),i=full.indexUnchecked(x,y,z);
						var substance=aquifer.computeSubstance(bx,by,bz,reference.get(i));
						bits(TerrainDecisionData.encode(substance,aquifer.shouldScheduleFluidUpdate(),settings),codes.get(i));
						assertSame(substance,field.fillSubstance(bx,by,bz));
						assertEquals(aquifer.shouldScheduleFluidUpdate(),field.lastDecisionFluidUpdate());
					}
					assertEquals(98304,field.decisionSamplesServed());
					var wrapped=field.wrapSurfaceSamplers(server,settings,full,chunk.cachingSamplers());
					field.requireDecisionFill(full,settings);
					var shifted=new DensityVolume(16,job.height(),16,full.minBlockX()+1,full.minBlockY(),full.minBlockZ());
					assertThrows(IllegalArgumentException.class,()->field.requireDecisionFill(shifted,settings));
					assertSame(chunk.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampler(),wrapped.get(settings.noiseRouter().finalDensity()).sampler());
					try(var a=chunk.cachingSamplers().get(settings.noiseRouter().chunkSurfaceLevel()).sampleVolume(SurfaceDensityData.materialVolume(job));
						var b=wrapped.get(settings.noiseRouter().chunkSurfaceLevel()).sampleVolume(SurfaceDensityData.materialVolume(job))) {
						for(int i=0;i<256;i++) bits(a.get(i),b.get(i));
					}
					// Independently compute every aligned cell before any full volume on
					// this sampler: no full-volume cache can hide rounding differences.
					if(key==NoiseGeneratorSettings.OVERWORLD && seed==8675309L) {
						var pool=validator.acquireDensityBufferPool();
						try {
							var isolated=validator.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
							for(int cell=0;cell<768;cell++) {
								var v=BlockDensityData.validationCell(job,cell);
								try(var b=isolated.get(settings.noiseRouter().finalDensity()).sampleVolume(v)) {
									for(int z=0;z<4;z++) for(int x=0;x<4;x++) for(int y=0;y<8;y++) {
										bits(reference.get(full.indexOfBlock(v.blockX(x),v.blockY(y),v.blockZ(z))),b.get(v.indexUnchecked(x,y,z)));
									}
								}
							}
						} finally {validator.releaseDensityBufferPool(pool);}
					}
					// Unprimed aquifer queries used by subset validation/carvers must
					// agree with vanilla's full-fill visitation and update flags.
					var fresh=TerrainDecisionData.aquifer(validator,settings,validator.samplersWithContext(SamplerContext.EMPTY_UNCACHED),full);
					var points=new Random(4);
					for(int i=0;i<128;i++) {
						int x=full.blockX(points.nextInt(16)),y=full.blockY(points.nextInt(job.height())),z=full.blockZ(points.nextInt(16));
						assertSame(aquifer.computeSubstance(x,y,z,0),fresh.computeSubstance(x,y,z,0));
						assertEquals(aquifer.shouldScheduleFluidUpdate(),fresh.shouldScheduleFluidUpdate());
					}
				}
				var metrics=RemoteDensityValidator.validate(job,result,8,validator,settings,noise,new Random(2));
				assertEquals(16,metrics.sampledCells());assertTrue(metrics.sampledValues()>=1025 && metrics.sampledValues()<=1152);
				// The full-density control shares the new aligned validation path.
				var block=new TerrainDensityJob(id,seed,true,key.identifier(),noise.minY(),noise.height(),1,1,TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE);
				assertTrue(RemoteDensityValidator.validate(block,BlockDensityData.sample(block,client,settings),8,validator,settings,noise,new Random(2)).sampledValues()>=1025);
				var selection=new Random(2);var v=BlockDensityData.validationCell(job,RemoteDensityValidator.selectCells(768,8,selection)[0]);
				int selected=full.indexOfBlock(v.minBlockX(),v.minBlockY(),v.minBlockZ());
				int surface=98304+RemoteDensityValidator.selectCells(49,8,selection)[0]*16;
				for(int index:new int[]{selected,surface}) {
					var changed=result.densities();changed[index]=index<98304?((int)changed[index]+1)%7:changed[index]+2;
					assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,()->RemoteDensityValidator.validate(job,new TerrainDensityResult(id,changed,0),8,validator,settings,noise,new Random(2)));
				}
				var malformed=result.densities();malformed[0]=0.5;
				assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,()->RemoteDensityValidator.validate(job,new TerrainDensityResult(id,malformed,0),0,validator,settings,noise,new Random(2)));
				assertThrows(IllegalArgumentException.class,()->new RemoteDensityField(job,new TerrainDensityResult(id,malformed,0),settings,server));
				System.out.printf("TERRAIN_DECISION_TRANSPORT settings=%s seed=%d encoded_bytes=%d validation_ms=%.3f%n",key.identifier(),seed,envelope.encodedDensityBytes(),metrics.elapsedNanos()/1e6);
			}
		}
	}
	@Test void operatorGateCodesAndTypedCacheAreBounded() {
		var lookup=VanillaRegistries.createWorldLookup();var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
		var dim=NoiseGeneratorSettings.OVERWORLD.identifier();var fp=WorldgenContextFingerprint.fromBytes(new byte[32]);var owner=UUID.randomUUID();
		var id=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),dim,0,0,fp);
		var key=new RemoteDensityResultCache.Key(1,dim,0,0,fp,dim,-64,384,1,1,owner,1,TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE);
		var grid=new RemoteDensityResultCache.Key(1,dim,0,0,fp,dim,-64,384,1,1,owner,1,TerrainWorkKind.GRID_AND_SURFACE);
		var cache=new RemoteDensityResultCache(2);var result=new TerrainDensityResult(id,new double[99073],0);
		cache.put(key,result);assertTrue(cache.takeResult(grid).isEmpty());assertSame(result,cache.takeResult(key).orElseThrow());
		assertThrows(IllegalArgumentException.class,()->TerrainWorkKind.fromWire(6));assertEquals(12,WorldgenProtocolVersion.CURRENT.value());
		assertThrows(IllegalArgumentException.class,()->new WorldgenProtocolVersion(8).requireSupported());
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityJob(id,0,true,dim,-63,384,1,1,TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE));
		assertThrows(IllegalArgumentException.class,()->new RemoteDensityResultCache.Key(1,dim,0,0,fp,dim,-64,384,4,8,owner,1,TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE));
		for(double code:new double[]{-1,7,0.5,Double.NaN,Double.POSITIVE_INFINITY}) assertFalse(TerrainDecisionData.validCode(code));
		for(int code=0;code<=6;code++) assertTrue(TerrainDecisionData.validCode(code));
		String mode=System.getProperty("worldgen_assist.remote.work_kind"),allow=System.getProperty("worldgen_assist.remote.allow_terrain_decisions");
		try {
			System.setProperty("worldgen_assist.remote.work_kind","decisions");System.setProperty("worldgen_assist.remote.allow_terrain_decisions","false");
			assertEquals(TerrainWorkKind.GRID_AND_SURFACE,SurfaceDensityData.selectedKind(dim,settings));
			System.setProperty("worldgen_assist.remote.allow_terrain_decisions","true");assertEquals(TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE,SurfaceDensityData.selectedKind(dim,settings));
		} finally {
			if(mode==null)System.clearProperty("worldgen_assist.remote.work_kind");else System.setProperty("worldgen_assist.remote.work_kind",mode);
			if(allow==null)System.clearProperty("worldgen_assist.remote.allow_terrain_decisions");else System.setProperty("worldgen_assist.remote.allow_terrain_decisions",allow);
		}
	}
	private static void bits(float a,float b){assertEquals(Float.floatToRawIntBits(a),Float.floatToRawIntBits(b));}
}
