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
			assertFalse(coordinator.ownerHasCapacity(owner));
			assertEquals(16,coordinator.handleHello(owner,new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT,64,"test")).maxInFlightJobs());
			assertTrue(coordinator.ownerHasCapacity(owner));
			assertEquals(1,coordinator.ownerJobLimit(owner));
			for(int i=0;i<30;i++)coordinator.recordValidated(owner);
			assertEquals(16,coordinator.ownerJobLimit(owner));
		}
		var jobs=new ArrayList<RemoteJobCoordinator.Submission>();
		for(int i=0;i<16;i++) {jobs.add(submit(coordinator,a,i).orElseThrow());jobs.add(submit(coordinator,b,i).orElseThrow());}
		assertEquals(32,coordinator.pendingCount());assertTrue(submit(coordinator,a,99).isEmpty());assertTrue(submit(coordinator,b,99).isEmpty());
		assertFalse(coordinator.ownerHasCapacity(a));assertFalse(coordinator.ownerHasCapacity(b));
		coordinator.disconnect(a);assertEquals(16,coordinator.pendingCount());
		assertFalse(coordinator.ownerHasCapacity(a));
		for(int i=0;i<jobs.size();i++)assertEquals(i%2==0,jobs.get(i).result().isCompletedExceptionally());
		coordinator.disconnect(b);assertEquals(0,coordinator.pendingCount());
		assertFalse(coordinator.ownerHasCapacity(b));
	}
	@Test void capacityHintDoesNotReserveAndTracksCancelSendFailureAndQuarantine() {
		var fail=new java.util.concurrent.atomic.AtomicBoolean();
		var sender=new Sender(){@Override public void sendJob(UUID owner,TerrainJobRequestPayload payload){if(fail.get())throw new IllegalStateException("expected send failure");}};
		var coordinator=new RemoteJobCoordinator(new RemoteWorldgenConfig(true,4,Duration.ofSeconds(30)),sender,4);
		var owner=UUID.randomUUID();
		assertFalse(coordinator.ownerHasCapacity(owner));
		coordinator.handleHello(owner,new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT,4,"test"));
		for(int i=0;i<100;i++)assertTrue(coordinator.ownerHasCapacity(owner));
		assertEquals(0,coordinator.pendingCount());assertEquals(1,coordinator.ownerJobLimit(owner));
		var first=submit(coordinator,owner,1).orElseThrow();assertFalse(coordinator.ownerHasCapacity(owner));
		coordinator.cancelJob(owner,first.job().identity());assertTrue(coordinator.ownerHasCapacity(owner));
		for(int i=0;i<6;i++)coordinator.recordValidated(owner);
		assertEquals(4,coordinator.ownerJobLimit(owner));
		fail.set(true);assertTrue(submit(coordinator,owner,2).isEmpty());
		assertEquals(0,coordinator.pendingCount());assertTrue(coordinator.ownerHasCapacity(owner));
		coordinator.quarantine(owner);assertFalse(coordinator.ownerHasCapacity(owner));
		coordinator.shutdown();assertFalse(coordinator.ownerHasCapacity(owner));
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
	@Test void completeHintPolicyKeepsJobDeadlinesSeparateAndRequiresExplicitCompleteChoice() {
		String kind="worldgen_assist.remote.work_kind", allowed="worldgen_assist.remote.allow_complete_terrain";
		String oldKind=System.getProperty(kind),oldAllowed=System.getProperty(allowed);
		var deadline=Duration.ofSeconds(30);
		try {
			System.setProperty(kind,"density");System.setProperty(allowed,"true");
			assertEquals(1024,RemotePipelineOptions.prefetchCapacity(64));
			assertEquals(deadline.toNanos(),RemotePipelineOptions.prefetchHintNanos(deadline));
			System.setProperty(kind,"complete");System.setProperty(allowed,"false");
			assertEquals(1024,RemotePipelineOptions.prefetchCapacity(64));
			System.setProperty(allowed,"true");
			assertEquals(16_384,RemotePipelineOptions.prefetchCapacity(64));
			assertEquals(2048,RemotePipelineOptions.prefetchCapacity(8));
			assertEquals(Duration.ofSeconds(180).toNanos(),RemotePipelineOptions.prefetchHintNanos(deadline));
			assertEquals(Duration.ofSeconds(30),new RemoteWorldgenConfig(true,64,deadline).jobTimeout());
		} finally {
			if(oldKind==null)System.clearProperty(kind);else System.setProperty(kind,oldKind);
			if(oldAllowed==null)System.clearProperty(allowed);else System.setProperty(allowed,oldAllowed);
		}
	}
	private static class Sender implements RemoteJobSender {
		public boolean canSend(UUID owner){return true;}
		public void sendJob(UUID owner,TerrainJobRequestPayload payload){}
		public void sendCancel(UUID owner,TerrainJobCancelPayload payload){}
	}
}
