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

class SurfaceDensity263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test @Timeout(90) void surfaceTransportSamplingAndSecretValidationAreExact() {
		var lookup = VanillaRegistries.createWorldLookup();
		for (var key : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES)) {
			var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
			for (long seed : new long[] {8675309L, -987654321L}) {
				var client = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings);
				var server = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings);
				var identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(),
					Identifier.parse("minecraft:overworld"), -7, 11, WorldgenContextFingerprint.fromBytes(new byte[32]));
				var noise = settings.noiseSettings();
				var job = new TerrainDensityJob(identity, seed, true, key.identifier(), noise.minY(), noise.height(), 1, 1, TerrainWorkKind.SURFACE_FIELDS);
				var result = TerrainDensityResultEnvelope.encode(SurfaceDensityData.sample(job, client, settings)).decode();
				assertEquals(769, result.densityCount());
				assertEquals(769, RemoteDensityValidator.validate(job, result, 64, server, settings, noise, new Random(1)).sampledValues());
				assertTrue(RemoteDensityValidator.validate(job, result, 8, server, settings, noise, new Random(2)).sampledValues() <= 128);
				var field = new RemoteDensityField(job, result, settings, server);
				var pool = server.acquireDensityBufferPool();
				try {
					var original = server.samplersWithContext(SamplerContext.builder().useBufferArena(pool).enableCaches().build());
					var generation = new DensityVolume(16, job.height(), 16, -7*16, job.minY(), 11*16);
					var remote = field.wrapSurfaceSamplers(server, settings, generation, original);
					assertSame(original, field.wrapSurfaceSamplers(client, settings, generation, original));
					var aquifer = settings.aquifers().orElseThrow().surfaceLevel();
					var material = settings.noiseRouter().chunkSurfaceLevel();
					assertVolume(original.get(aquifer), remote.get(aquifer), SurfaceDensityData.aquiferVolume(job));
					assertVolume(original.get(aquifer), remote.get(aquifer), new DensityVolume(11, 1, 11, -7*16-16, 0, 11*16-16, 4, 1, 4));
					assertVolume(original.get(material), remote.get(material), SurfaceDensityData.materialVolume(job));
					assertVolume(original.get(material), remote.get(material), new DensityVolume(1, 1, 1, -7*16+3, 0, 11*16+5));
					var base = SurfaceDensityData.aquiferVolume(job);
					for (int z=0; z<base.sizeZ(); z++) for (int x=0; x<base.sizeX(); x++) {
						assertBits(original.sampleValue(aquifer, base.blockX(x), 0, base.blockZ(z)),
							remote.sampleValue(aquifer, base.blockX(x), 0, base.blockZ(z)));
						assertBits(original.sampleValue(aquifer, base.blockX(x)+1, 0, base.blockZ(z)),
							remote.sampleValue(aquifer, base.blockX(x)+1, 0, base.blockZ(z)));
					}
					assertVolume(original.get(aquifer), remote.get(aquifer), new DensityVolume(2, 1, 2, -7*16-68, 0, 11*16-32, 4, 1, 4));
					assertTrue(field.remoteSamplesServed() > 1000);
				} finally { server.releaseDensityBufferPool(pool); }
				var changed = result.densities();
				int selected = RemoteDensityValidator.selectCells(49, 8, new Random(2))[0]*16;
				changed[selected] += 1;
				assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,
					() -> RemoteDensityValidator.validate(job, new TerrainDensityResult(identity, changed, 0), 8, server, settings, noise, new Random(2)));
			}
		}
	}

	@Test void typedCacheRejectsCrossKindResultsAndInvalidJobs() {
		var dim = Identifier.parse("minecraft:overworld"); var fp = WorldgenContextFingerprint.fromBytes(new byte[32]);
		UUID owner = UUID.randomUUID();
		var density = new RemoteDensityResultCache.Key(1, dim, 0, 0, fp, dim, -64, 384, 1, 1, owner, 1);
		var surface = new RemoteDensityResultCache.Key(1, dim, 0, 0, fp, dim, -64, 384, 1, 1, owner, 1, TerrainWorkKind.SURFACE_FIELDS);
		var identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), dim, 0, 0, fp);
		var cache = new RemoteDensityResultCache(2);
		var result = new TerrainDensityResult(identity, new double[769], 0);
		cache.put(surface, result); assertTrue(cache.takeResult(density).isEmpty()); assertSame(result, cache.takeResult(surface).orElseThrow());
		assertThrows(IllegalArgumentException.class, () -> cache.put(density, result));
		assertThrows(IllegalArgumentException.class, () -> TerrainWorkKind.fromWire(6));
		assertThrows(IllegalArgumentException.class, () -> new TerrainDensityJob(identity, 0, true, dim, -64, 384, 4, 8, TerrainWorkKind.SURFACE_FIELDS));
		assertNull(RemoteDensitySamplingScope.current());
	}

	private static void assertVolume(DensitySampler.Bound expected, DensitySampler.Bound actual, DensityVolume volume) {
		try (var a=expected.sampleVolume(volume); var b=actual.sampleVolume(volume)) {
			for (int i=0;i<volume.size();i++) assertBits(a.get(i),b.get(i));
		}
	}
	private static void assertBits(float expected, float actual) { assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual)); }
}
