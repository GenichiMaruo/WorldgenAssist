package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import io.github.genichimaruo.worldgenassist.common.*;

class RemoteDensityScope263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void queuedClaimIsSingleUseAndNestedScopesRestoreTheirField() {
		var outer = new Target(); var inner = new Target();
		var a = field(0); var b = field(1); inner.field = b;
		var calls = new AtomicInteger(); var completed = new AtomicInteger();
		var opportunity = new RemoteDensityOpportunity(() -> { calls.incrementAndGet(); outer.field=a; return a; },
			(f,elapsed,error) -> { assertSame(a,f); assertNull(error); assertTrue(elapsed>=0); completed.incrementAndGet(); });
		outer.opportunity=opportunity;
		int output=RemoteDensitySamplingScope.run(outer,() -> {
			assertSame(a,RemoteDensitySamplingScope.current());
			RemoteDensitySamplingScope.run(inner,() -> { assertSame(b,RemoteDensitySamplingScope.current()); return 0; });
			assertSame(a,RemoteDensitySamplingScope.current()); return 7;
		});
		assertEquals(7,output);
		assertNull(RemoteDensitySamplingScope.current()); assertNull(outer.field); assertNull(inner.field);
		assertNull(outer.opportunity); assertNull(opportunity.take()); assertEquals(1,calls.get()); assertEquals(1,completed.get());
	}
	@Test void missingRevokedOrFailedClaimsKeepLocalGenerationAndErrorsRestoreTheScope() {
		var target = new Target(); var cache = new RemoteDensityResultCache(1); var a = field(0);
		var owner=UUID.randomUUID(); var dim=Identifier.parse("minecraft:overworld");
		var fp=WorldgenContextFingerprint.fromBytes(new byte[32]);
		var key=new RemoteDensityResultCache.Key(1,dim,0,0,fp,dim,0,1,1,1,owner,1);
		var identity=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,a.jobId(),dim,0,0,fp);
		cache.put(key,new TerrainDensityResult(identity,new double[256],0)); cache.removeOwner(owner);
		target.opportunity=new RemoteDensityOpportunity(() -> cache.takeResult(key).map(r -> a).orElse(null),
			(f,n,e) -> fail("An absent/revoked field must not be counted as applied"));
		int local=RemoteDensitySamplingScope.run(target,() -> { assertNull(RemoteDensitySamplingScope.current()); return 3; });
		assertEquals(3,local);
		target.opportunity=new RemoteDensityOpportunity(() -> { throw new IllegalArgumentException("expired shape"); },(f,n,e)->fail());
		int rejected=RemoteDensitySamplingScope.run(target,() -> 4); assertEquals(4,rejected); assertNull(RemoteDensitySamplingScope.current());
		target.field=a;
		assertThrows(IllegalStateException.class,() -> RemoteDensitySamplingScope.run(target,() -> {throw new IllegalStateException();}));
		assertNull(target.field); assertNull(RemoteDensitySamplingScope.current());
	}
	private static RemoteDensityField field(int x) {
		var dim=Identifier.parse("minecraft:overworld");
		var identity=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),dim,x,0,WorldgenContextFingerprint.fromBytes(new byte[32]));
		var job=new TerrainDensityJob(identity,8675309L,true,dim,0,1,1,1);
		return new RemoteDensityField(job,new TerrainDensityResult(identity,new double[256],0));
	}
	private static final class Target implements RemoteDensityTarget {
		RemoteDensityField field; RemoteDensityOpportunity opportunity;
		public void worldgenAssist$installRemoteDensity(RemoteDensityField value){field=value;}
		public void worldgenAssist$clearRemoteDensity(UUID id){if(field!=null && field.jobId().equals(id))field=null;}
		public RemoteDensityField worldgenAssist$getRemoteDensity(){return field;}
		public void worldgenAssist$installRemoteOpportunity(RemoteDensityOpportunity value){opportunity=value;}
		public RemoteDensityOpportunity worldgenAssist$takeRemoteOpportunity(){var value=opportunity;opportunity=null;return value;}
		public void worldgenAssist$clearRemoteOpportunity(RemoteDensityOpportunity value){if(opportunity==value)opportunity=null;}
	}
}
