package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import io.github.genichimaruo.worldgenassist.common.*;

class CooperativeBackend263Test {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void cooperativeDefaultsAreBoundedAndExplicitOverridesRemainAuthoritative(){
        assertEquals(new NoiseStageBackendConfig(NoiseStageBackendConfig.Mode.VANILLA,1,1),
            NoiseStageBackendConfig.resolve(null,null,null,2));
        assertEquals(new NoiseStageBackendConfig(NoiseStageBackendConfig.Mode.LOCAL,1,1),
            NoiseStageBackendConfig.resolve("local",null,null,2));
        assertEquals(new NoiseStageBackendConfig(NoiseStageBackendConfig.Mode.COOPERATIVE,2,4),
            NoiseStageBackendConfig.resolve(" cooperative ",null,null,2));
        assertEquals(1,NoiseStageBackendConfig.resolve("cooperative",null,null,1).workerThreads());
        assertEquals(2,NoiseStageBackendConfig.resolve("cooperative",null,null,36).workerThreads());
        assertEquals(new NoiseStageBackendConfig(NoiseStageBackendConfig.Mode.COOPERATIVE,3,0),
            NoiseStageBackendConfig.resolve("cooperative","3","0",2));
        assertEquals(1,NoiseStageBackendConfig.resolve("cooperative","65","17",2).workerThreads());
        assertTrue(NoiseStageBackendConfig.Mode.COOPERATIVE.usesLocalWorkers());
        assertFalse(NoiseStageBackendConfig.Mode.VANILLA.usesLocalWorkers());
    }
    @Test void independentChunksRunConcurrentlyWithIsolatedRemoteDensityAndReusableWorkers() throws Exception {
        var backend=new LocalWorldgenTaskBackend(2,8,"CAWG-Cooperative-Test-");
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);
        var a=new Target(field(0));var b=new Target(field(1));
        var first=new CompletableFuture<String>();var second=new CompletableFuture<String>();
        try {
            execute(backend,a,first,started,release,0);
            execute(backend,b,second,started,release,1);
            assertTrue(started.await(5,TimeUnit.SECONDS),"Both independent chunks must enter before either completes");
            assertEquals(2,backend.snapshot().activeTasks());
            release.countDown();
            assertNotEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
            assertNull(a.field);assertNull(b.field);
            var clean=new CompletableFuture<Void>();
            backend.executorFor("noise",new ChunkPos(2,0),task->fail("Unexpected fallback")).execute(()->{
                try{assertNull(RemoteDensitySamplingScope.current());clean.complete(null);}
                catch(Throwable error){clean.completeExceptionally(error);}
            });
            clean.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();backend.close();}
    }
    private static void execute(LocalWorldgenTaskBackend backend,Target target,CompletableFuture<String> result,
        CountDownLatch started,CountDownLatch release,int x){
        var expected=target.field;
        backend.executorFor("noise",new ChunkPos(x,0),task->fail("Unexpected fallback")).execute(()->{
            try {
                RemoteDensitySamplingScope.run(target,()->{
                    assertSame(expected,RemoteDensitySamplingScope.current());started.countDown();
                    try{assertTrue(release.await(5,TimeUnit.SECONDS));}
                    catch(InterruptedException error){throw new AssertionError(error);}
                    assertSame(expected,RemoteDensitySamplingScope.current());return 0;
                });
                assertNull(RemoteDensitySamplingScope.current());result.complete(Thread.currentThread().getName());
            }catch(Throwable error){result.completeExceptionally(error);}
        });
    }
    private static RemoteDensityField field(int x){
        var dim=Identifier.parse("minecraft:overworld");
        var id=new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),dim,x,0,WorldgenContextFingerprint.fromBytes(new byte[32]));
        var job=new TerrainDensityJob(id,8675309L,true,dim,0,1,1,1);
        return new RemoteDensityField(job,new TerrainDensityResult(id,new double[256],0));
    }
    private static final class Target implements RemoteDensityTarget {
        RemoteDensityField field;Target(RemoteDensityField field){this.field=field;}
        public RemoteDensityField worldgenAssist$getRemoteDensity(){return field;}
        public void worldgenAssist$installRemoteDensity(RemoteDensityField value){field=value;}
        public void worldgenAssist$clearRemoteDensity(UUID id){if(field!=null&&field.jobId().equals(id))field=null;}
        public void worldgenAssist$installRemoteOpportunity(RemoteDensityOpportunity value){throw new UnsupportedOperationException();}
        public RemoteDensityOpportunity worldgenAssist$takeRemoteOpportunity(){return null;}
        public void worldgenAssist$clearRemoteOpportunity(RemoteDensityOpportunity value){}
    }
}
