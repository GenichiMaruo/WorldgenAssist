package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import io.github.genichimaruo.worldgenassist.common.*;
import io.github.genichimaruo.worldgenassist.network.*;

class RemoteWindow263Test {
	@BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
	@Test void wideWindowPreservesOwnerAndGlobalLimitsAndOwnerCleanup() {
		var coordinator=new RemoteJobCoordinator(new RemoteWorldgenConfig(true,32,Duration.ofSeconds(30)),new Sender(),16);
		var a=UUID.randomUUID();var b=UUID.randomUUID();
		for(var owner:List.of(a,b)) {
			assertEquals(16,coordinator.handleHello(owner,new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT,64,"test")).maxInFlightJobs());
			assertEquals(1,coordinator.ownerJobLimit(owner));
			for(int i=0;i<30;i++)coordinator.recordValidated(owner);
			assertEquals(16,coordinator.ownerJobLimit(owner));
		}
		var jobs=new ArrayList<RemoteJobCoordinator.Submission>();
		for(int i=0;i<16;i++) {jobs.add(submit(coordinator,a,i).orElseThrow());jobs.add(submit(coordinator,b,i).orElseThrow());}
		assertEquals(32,coordinator.pendingCount());assertTrue(submit(coordinator,a,99).isEmpty());assertTrue(submit(coordinator,b,99).isEmpty());
		coordinator.disconnect(a);assertEquals(16,coordinator.pendingCount());
		for(int i=0;i<jobs.size();i++)assertEquals(i%2==0,jobs.get(i).result().isCompletedExceptionally());
		coordinator.disconnect(b);assertEquals(0,coordinator.pendingCount());
	}
	@Test void globalAndAdvertisedLimitsClampWidePolicyAndRefillRemainsBounded() {
		var coordinator=new RemoteJobCoordinator(new RemoteWorldgenConfig(true,8,Duration.ofSeconds(30)),new Sender(),16);
		assertEquals(8,coordinator.handleHello(UUID.randomUUID(),new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT,64,"test")).maxInFlightJobs());
		assertEquals(3,coordinator.handleHello(UUID.randomUUID(),new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT,3,"test")).maxInFlightJobs());
		assertEquals(2,RemotePipelineOptions.refillWatermark(8,4));assertEquals(16,RemotePipelineOptions.refillWatermark(32,16));assertEquals(8,RemotePipelineOptions.refillWatermark(8,16));
		assertThrows(IllegalArgumentException.class,()->new RemoteJobCoordinator(new RemoteWorldgenConfig(true,8,Duration.ofSeconds(30)),new Sender(),65));
	}
	private static Optional<RemoteJobCoordinator.Submission> submit(RemoteJobCoordinator coordinator,UUID owner,int x) {
		return coordinator.trySubmitForOwner(owner,Identifier.parse("minecraft:overworld"),x,0,WorldgenContextFingerprint.fromBytes(new byte[32]),
			id->new TerrainDensityJob(id,8675309,true,Identifier.parse("minecraft:overworld"),0,8,1,1));
	}
	private static class Sender implements RemoteJobSender {
		public boolean canSend(UUID owner){return true;}
		public void sendJob(UUID owner,TerrainJobRequestPayload payload){}
		public void sendCancel(UUID owner,TerrainJobCancelPayload payload){}
	}
}
