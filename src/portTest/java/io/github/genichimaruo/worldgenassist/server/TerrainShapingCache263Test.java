package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import net.minecraft.resources.Identifier;
import io.github.genichimaruo.worldgenassist.common.*;

class TerrainShapingCache263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void shapingSeparatesCachedResultsWithoutResettingTheOwnerAuditCohort() {
		var dim = Identifier.withDefaultNamespace("overworld"); var fp = WorldgenContextFingerprint.fromBytes(new byte[32]);
		var owner = UUID.randomUUID(); var box = new TerrainBeardifierData.Box(0, 50, 0, 10, 70, 10);
		var shape = new TerrainBeardifierData(List.of(new TerrainBeardifierData.Rigid(box, 2, 0)), List.of(), box);
		var emptyKey = new RemoteDensityResultCache.Key(1, dim, 0, 0, fp, dim, -64, 16, 1, 1, owner, 2, TerrainWorkKind.COMPLETE_TERRAIN);
		var shapedKey = new RemoteDensityResultCache.Key(1, dim, 0, 0, fp, dim, -64, 16, 1, 1, owner, 2, TerrainWorkKind.COMPLETE_TERRAIN, shape);
		assertNotEquals(emptyKey, shapedKey);
		assertEquals(RemoteWorldgenManager.auditContext(emptyKey), RemoteWorldgenManager.auditContext(shapedKey));
		var rejoined = new RemoteDensityResultCache.Key(1, dim, 0, 0, fp, dim, -64, 16, 1, 1, owner, 3, TerrainWorkKind.COMPLETE_TERRAIN, shape);
		assertNotEquals(RemoteWorldgenManager.auditContext(shapedKey), RemoteWorldgenManager.auditContext(rejoined));
		var identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), dim, 0, 0, fp);
		var terrain = new CompleteTerrainData(-64, 16, new byte[4096], new short[256], new short[256], new short[][]{new short[0]}, new byte[32]);
		var result = TerrainDensityResult.fromCompleteTerrain(identity, terrain, 0);
		var cache = new RemoteDensityResultCache(2); cache.put(shapedKey, result);
		assertFalse(cache.available(emptyKey)); assertTrue(cache.takeResult(emptyKey).isEmpty()); assertSame(result, cache.takeResult(shapedKey).orElseThrow());
		assertThrows(IllegalArgumentException.class, () -> new TerrainDensityJob(identity, 0, true, dim, -64, 16, 1, 1, TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE, shape));
		assertThrows(IllegalArgumentException.class, () -> new RemoteDensityResultCache.Key(1, dim, 0, 0, fp, dim, -64, 16, 1, 1, owner, 2, TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE, shape));
	}
}
