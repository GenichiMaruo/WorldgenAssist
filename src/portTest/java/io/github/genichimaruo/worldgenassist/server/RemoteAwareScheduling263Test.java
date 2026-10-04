package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.genichimaruo.worldgenassist.server.RemoteDensityOpportunity.Availability.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RemoteAwareScheduling263Test {
	@org.junit.jupiter.api.BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
	}
	@Test void choosesReadyThenIndependentWorkWithoutWaitingOrStarvingPendingWork() {
		var queue = new RemoteAwareTerrainQueue(); var order = new ArrayList<String>();
		var pending = new AtomicReference<>(PENDING);
		assertTrue(queue.offer(() -> order.add("pending"), pending::get, 1));
		assertFalse(queue.offer(() -> order.add("local"), () -> LOCAL, 1));
		assertFalse(queue.offer(() -> order.add("ready"), () -> READY, 1));
		queue.poll().run(); queue.poll().run();
		assertEquals(List.of("ready","local"),order);
		pending.set(READY); queue.poll().run(); assertNull(queue.poll());
		assertEquals(List.of("ready","local","pending"),order); assertEquals(2,queue.reordered());
		assertTrue(queue.offer(() -> order.add("forced"), () -> PENDING, 1));
		for (int i=0;i<9;i++) queue.offer(() -> order.add("new-ready"), () -> READY, 1);
		for (int i=0;i<9;i++) queue.poll().run();
		assertEquals("forced",order.get(11)); // At most eight bypasses; never wait for replies.
		queue.poll().run(); assertNull(queue.poll()); assertEquals(0,queue.size());
		assertTrue(queue.offer(() -> order.add("pending-alone"), () -> PENDING, 1));
		queue.poll().run(); assertNull(queue.poll()); assertEquals("pending-alone",order.getLast());
	}
	@Test @Timeout(15) void priorityExecutionKeepsAdmissionFailureAndRestartSafe() throws Exception {
		var backend = new LocalWorldgenTaskBackend(1,4,"CAWG-Priority-Test-",true);
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
		var order = Collections.synchronizedList(new ArrayList<String>());
		var pending = new AtomicReference<>(PENDING); var fallback = new AtomicBoolean();
		var pos = new ChunkPos(1,2);
		try {
			backend.executorFor("noise",pos,Runnable::run).execute(() -> {
				entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("release"); }
				catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
			});
			assertTrue(entered.await(5,TimeUnit.SECONDS));
			backend.executorFor("noise",pos,Runnable::run,pending::get).execute(() -> order.add("pending"));
			backend.executorFor("noise",pos,Runnable::run,() -> LOCAL).execute(() -> order.add("local"));
			backend.executorFor("noise",pos,Runnable::run,() -> READY).execute(() -> {order.add("ready");pending.set(READY);});
			backend.executorFor("noise",pos,Runnable::run).execute(() -> {throw new IllegalStateException("expected command failure");});
			backend.executorFor("noise",pos,Runnable::run).execute(() -> fallback.set(true));
			assertTrue(fallback.get()); assertEquals(5,backend.snapshot().admittedTasks());
			assertEquals(4,backend.snapshot().queuedTasks()); assertEquals(1,backend.snapshot().vanillaFallbacks());
			release.countDown(); awaitCompleted(backend,5);
			assertEquals(List.of("ready","pending","local"),order);
			assertEquals(5,backend.snapshot().availableAdmissionPermits()); assertEquals(0,backend.snapshot().activeTasks());
			backend.close(); var stopped = new AtomicBoolean();
			backend.executorFor("noise",pos,Runnable::run).execute(() -> stopped.set(true)); assertTrue(stopped.get());
			backend.start(); var next = new CompletableFuture<String>();
			backend.executorFor("noise",pos,Runnable::run).execute(() -> next.complete(Thread.currentThread().getName()));
			assertTrue(next.get(5,TimeUnit.SECONDS).startsWith("CAWG-Priority-Test-")); awaitCompleted(backend,6);
			assertEquals(5,backend.snapshot().availableAdmissionPermits());
		} finally {release.countDown();backend.close();}
	}
	@Test void availabilityIsOnlyAHintAndDefaultQueueRemainsBounded() {
		var claims = new AtomicInteger(); var state = new AtomicReference<>(PENDING);
		var opportunity = new RemoteDensityOpportunity(() -> {claims.incrementAndGet();return null;},(f,n,e) -> {},state::get);
		assertEquals(PENDING,opportunity.availability()); state.set(READY);
		assertEquals(READY,opportunity.availability()); assertEquals(0,claims.get());
		assertNull(opportunity.take()); assertEquals(LOCAL,opportunity.availability()); assertNull(opportunity.take());assertEquals(1,claims.get());
		var automatic = NoiseStageBackendConfig.resolve("cooperative",null,null,8);
		assertEquals(2,automatic.workerThreads());assertEquals(16,automatic.queuedTasksPerWorker());
		assertEquals(32,LocalWorldgenTaskBackend.queueCapacityForWorkers(automatic.workerThreads(),automatic.queuedTasksPerWorker()));
		assertEquals(4,NoiseStageBackendConfig.resolve("cooperative",null,"4",8).queuedTasksPerWorker());
		assertEquals(1,NoiseStageBackendConfig.resolve("local",null,null,8).queuedTasksPerWorker());
	}
	private static void awaitCompleted(LocalWorldgenTaskBackend backend,long count) throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while((backend.snapshot().completedTasks()<count || backend.snapshot().admittedTasks()!=0) && System.nanoTime()<deadline)Thread.sleep(1);
		assertEquals(count,backend.snapshot().completedTasks());assertEquals(0,backend.snapshot().admittedTasks());
	}
}
