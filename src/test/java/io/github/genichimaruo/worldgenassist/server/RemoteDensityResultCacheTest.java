package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RemoteDensityResultCacheTest {
	private static final WorldgenContextFingerprint CONTEXT = WorldgenContextFingerprint.fromHex("ab".repeat(32));

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void usesAnAccessOrderedBoundAndDefensiveCopies() {
		RemoteDensityResultCache cache = new RemoteDensityResultCache(2);
		RemoteDensityResultCache.Key first = key(0, CONTEXT);
		RemoteDensityResultCache.Key second = key(1, CONTEXT);
		RemoteDensityResultCache.Key third = key(2, CONTEXT);

		cache.put(first, result(first, 1.0));
		cache.put(second, result(second, 2.0));
		assertTrue(cache.contains(first));
		double[] exposed = cache.get(first).orElseThrow();
		exposed[0] = 99.0;
		cache.put(third, result(third, 3.0));

		assertArrayEquals(result(first, 1.0).densities(), cache.get(first).orElseThrow());
		assertFalse(cache.get(second).isPresent());
		assertFalse(cache.contains(second));
		assertTrue(cache.get(third).isPresent());
		assertEquals(2, cache.size());
		assertEquals(2, cache.clear());
		assertEquals(0, cache.size());
	}

	@Test
	void zeroCapacityDisablesStorageAndShapeMismatchIsRejected() {
		RemoteDensityResultCache.Key key = key(0, CONTEXT);
		RemoteDensityResultCache disabled = new RemoteDensityResultCache(0);
		disabled.put(key, result(key, 1.0));
		assertTrue(disabled.get(key).isEmpty());

		RemoteDensityResultCache enabled = new RemoteDensityResultCache(1);
		TerrainDensityResult wrongShape = new TerrainDensityResult(identity(key), new double[1], 0L);
		assertThrows(IllegalArgumentException.class, () -> enabled.put(key, wrongShape));
	}

	@Test
	void contextAndGeometryArePartOfTheKey() {
		RemoteDensityResultCache cache = new RemoteDensityResultCache(2);
		RemoteDensityResultCache.Key key = key(0, CONTEXT);
		cache.put(key, result(key, 1.0));

		assertTrue(cache.get(key(0, CONTEXT)).isPresent());
		assertTrue(cache.get(key(0, WorldgenContextFingerprint.fromHex("cd".repeat(32)))).isEmpty());
		assertTrue(cache.get(key(1, CONTEXT)).isEmpty());
		assertTrue(cache.remove(key));
		assertFalse(cache.remove(key));
		assertTrue(cache.get(key).isEmpty());
	}

	private static RemoteDensityResultCache.Key key(int chunkX, WorldgenContextFingerprint context) {
		return new RemoteDensityResultCache.Key(
			0L,
			Identifier.parse("minecraft:overworld"),
			chunkX,
			-2,
			context,
			Identifier.parse("minecraft:overworld"),
			-64,
			8,
			4,
			8
		);
	}

	private static TerrainDensityResult result(RemoteDensityResultCache.Key key, double value) {
		double[] densities = new double[key.sampleCount()];
		java.util.Arrays.fill(densities, value);
		return new TerrainDensityResult(identity(key), densities, 0L);
	}

	private static TerrainJobIdentity identity(RemoteDensityResultCache.Key key) {
		return new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			UUID.randomUUID(),
			key.dimension(),
			key.chunkX(),
			key.chunkZ(),
			key.contextFingerprint()
		);
	}
}
