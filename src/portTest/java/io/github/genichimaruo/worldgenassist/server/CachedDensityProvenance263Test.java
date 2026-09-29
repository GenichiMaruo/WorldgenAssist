package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import io.github.genichimaruo.worldgenassist.common.*;

class CachedDensityProvenance263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void preservesRequestIdentityAndOwnerIsolationWhenConsumed() {
		var dimension = Identifier.parse("minecraft:overworld");
		var fingerprint = WorldgenContextFingerprint.fromBytes(new byte[32]);
		UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
		var key = new RemoteDensityResultCache.Key(1, dimension, 3, -5, fingerprint,
			Identifier.parse("minecraft:overworld"), 0, 4, 1, 1, owner, 1);
		var otherKey = new RemoteDensityResultCache.Key(1, dimension, 3, -5, fingerprint,
			key.noiseSettings(), 0, 4, 1, 1, other, 1);
		var identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), dimension, 3, -5, fingerprint);
		var result = new TerrainDensityResult(identity, new double[key.sampleCount()], 123);
		var cache = new RemoteDensityResultCache(2);
		cache.put(key, result);
		assertTrue(cache.takeResult(otherKey).isEmpty());
		var taken = cache.takeResult(key).orElseThrow();
		assertEquals(identity, taken.identity()); assertArrayEquals(result.densities(), taken.densities());
		assertEquals(123, taken.clientComputeNanos()); assertTrue(cache.takeResult(key).isEmpty());
		var wrong = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), dimension, 4, -5, fingerprint);
		assertThrows(IllegalArgumentException.class,
			() -> cache.put(key, new TerrainDensityResult(wrong, result.densities(), 0)));
		var wrongContext = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), dimension, 3, -5,
			WorldgenContextFingerprint.fromHex("01".repeat(32)));
		assertThrows(IllegalArgumentException.class,
			() -> cache.put(key, new TerrainDensityResult(wrongContext, result.densities(), 0)));
		cache.put(key, result); cache.removeOwner(owner); assertTrue(cache.takeResult(key).isEmpty());
	}
}
