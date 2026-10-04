package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import io.github.genichimaruo.worldgenassist.common.*;

class BlockDensity263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test @Timeout(120) void transportedDensityPreservesEveryBlockFluidDecisionAndCanonicalSampler() {
		var lookup = VanillaRegistries.createWorldLookup();
		for (var key : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES)) {
			var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
			for (long seed : key == NoiseGeneratorSettings.OVERWORLD ? new long[]{8675309L, -987654321L} : new long[]{8675309L}) {
				var client = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings);
				var server = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings);
				var noise = settings.noiseSettings();
				var id = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), Identifier.parse("minecraft:overworld"),
					-7, 11, WorldgenContextFingerprint.fromBytes(new byte[32]));
				var job = new TerrainDensityJob(id, seed, true, key.identifier(), noise.minY(), noise.height(), 1, 1, TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE);
				var envelope = TerrainDensityResultEnvelope.encode(BlockDensityData.sample(job, client, settings));
				var result = envelope.decode();
				assertEquals(99073, result.densityCount());
				var field = new RemoteDensityField(job, result, settings, server);
				var volume = BlockDensityData.volume(job);
				var pool = server.acquireDensityBufferPool();
				try {
					var original = server.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
					var remote = field.wrapSurfaceSamplers(server, settings, volume, original);
					// doFill alone consumes canonical values. Surface/carvers keep the original final sampler.
					assertSame(original.get(settings.noiseRouter().finalDensity()).sampler(), remote.get(settings.noiseRouter().finalDensity()).sampler());
					assertSame(original, field.wrapSurfaceSamplers(client, settings, volume, original));
					Aquifer.FluidPicker fluids = (x,y,z) -> new Aquifer.FluidStatus(y < -54 ? -54 : settings.seaLevel(),
						y < -54 ? Blocks.LAVA.defaultBlockState() : settings.defaultFluid());
					var config = settings.aquifers().orElseThrow();
					var factory = server.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer"));
					var vanilla = config.create(original, factory, volume, fluids);
					var assisted = config.create(remote, factory, volume, fluids);
					try (var reference = original.get(settings.noiseRouter().finalDensity()).sampleVolume(volume);
						var copy = SamplerContext.EMPTY_UNCACHED.acquireBuffer(volume)) {
						field.copyVolume(volume, copy);
						for (int z=0; z<16; z++) for (int x=0; x<16; x++) for (int y=job.height()-1; y>=0; y--) {
							int i = volume.indexUnchecked(x,y,z);
							bits(BlockDensityData.canonical(reference.get(i)), copy.get(i));
							assertSame(vanilla.computeSubstance(volume.blockX(x),volume.blockY(y),volume.blockZ(z),reference.get(i)),
								assisted.computeSubstance(volume.blockX(x),volume.blockY(y),volume.blockZ(z),copy.get(i)), "block " + i);
							assertEquals(vanilla.shouldScheduleFluidUpdate(), assisted.shouldScheduleFluidUpdate(), "fluid " + i);
						}
						assertThrows(IllegalArgumentException.class, () -> field.copyVolume(new DensityVolume(16,job.height(),16,-111,job.minY(),176),copy));
					}
					try (var a=original.get(settings.noiseRouter().chunkSurfaceLevel()).sampleVolume(SurfaceDensityData.materialVolume(job));
						var b=remote.get(settings.noiseRouter().chunkSurfaceLevel()).sampleVolume(SurfaceDensityData.materialVolume(job))) {
						for(int i=0;i<256;i++) bits(a.get(i),b.get(i));
					}
				} finally { server.releaseDensityBufferPool(pool); }
				var validation = RemoteDensityValidator.validate(job,result,8,server,settings,noise,new Random(2));
				assertEquals(16, validation.sampledCells());
				assertTrue(validation.sampledValues() >= 1025 && validation.sampledValues() <= 1152);
				var selection = new Random(2);
				var chosenCell = BlockDensityData.validationCell(job,RemoteDensityValidator.selectCells(768,8,selection)[0]);
				int densityIndex = volume.indexOfBlock(chosenCell.minBlockX(),chosenCell.minBlockY(),chosenCell.minBlockZ());
				int surfaceIndex = 98304 + RemoteDensityValidator.selectCells(49,8,selection)[0]*16;
				for(int index:new int[]{densityIndex,surfaceIndex}) {
					var changed=result.densities(); changed[index]+=2;
					assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class, () -> RemoteDensityValidator.validate(job,
						new TerrainDensityResult(id,changed,0),8,server,settings,noise,new Random(2)));
				}
				System.out.printf("BLOCK_DENSITY_TRANSPORT settings=%s seed=%d samples=%d raw_bytes=%d encoded_bytes=%d compute_ms=%.3f%n",
					key.identifier(),seed,result.densityCount(),envelope.rawDensityBytes(),envelope.encodedDensityBytes(),result.clientComputeNanos()/1e6);
			}
		}
	}
	@Test void shapeBoundsKindIsolationAndSigns() {
		bits(0f,BlockDensityData.canonical(0f)); bits(-0f,BlockDensityData.canonical(-0f));
		bits(-Float.MIN_VALUE,BlockDensityData.canonical(-Float.MIN_VALUE));
		bits(1f,BlockDensityData.canonical(Float.MIN_VALUE));
		var dim=Identifier.parse("minecraft:overworld"); var fp=WorldgenContextFingerprint.fromBytes(new byte[32]);
		var owner=UUID.randomUUID(); var id=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),dim,0,0,fp);
		var block=new RemoteDensityResultCache.Key(1,dim,0,0,fp,dim,-64,384,1,1,owner,1,TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE);
		var grid=new RemoteDensityResultCache.Key(1,dim,0,0,fp,dim,-64,384,1,1,owner,1,TerrainWorkKind.GRID_AND_SURFACE);
		assertEquals(99073,block.sampleCount()); assertEquals(98304,TerrainDensityJob.MAX_SAMPLE_COUNT);
		assertEquals(12,WorldgenProtocolVersion.CURRENT.value());
		assertThrows(IllegalArgumentException.class,()->new WorldgenProtocolVersion(10).requireSupported());
		assertThrows(IllegalArgumentException.class,()->new WorldgenProtocolVersion(6).requireSupported());
		var cache=new RemoteDensityResultCache(2); var result=new TerrainDensityResult(id,new double[99073],0);
		cache.put(block,result); assertTrue(cache.takeResult(grid).isEmpty()); assertSame(result,cache.takeResult(block).orElseThrow());
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityResult(id,new double[99074],0));
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityJob(id,0,true,dim,-64,384,4,8,TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE));
		assertEquals(TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE,TerrainWorkKind.fromWire(3));
		assertThrows(IllegalArgumentException.class,()->TerrainWorkKind.fromWire(6));
	}
	private static void bits(float a,float b) { assertEquals(Float.floatToRawIntBits(a),Float.floatToRawIntBits(b)); }
}
