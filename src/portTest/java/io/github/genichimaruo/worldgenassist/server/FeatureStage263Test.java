package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;

class FeatureStage263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void stockBurstAndAccessFootprintMatchTheAdmissionReservation() {
		assertEquals(FeatureStageConfig.OFF, FeatureStageConfig.parse(null));
		assertEquals(FeatureStageConfig.OFF, FeatureStageConfig.parse("unknown"));
		assertEquals(1, FeatureStageConfig.parse(" SERIAL ").workers());
		assertEquals(2, FeatureStageConfig.parse("parallel").workers());
		assertEquals(1, FeatureStageConfig.parse(" GUARDED ").workers());
		assertTrue(FeatureStageConfig.GUARDED.usesRegionalOwnership());
		assertTrue(FeatureStageConfig.PARALLEL.usesRegionalOwnership());
		assertFalse(FeatureStageConfig.SERIAL.usesRegionalOwnership());
		assertFalse(FeatureStageConfig.OFF.usesRegionalOwnership());
		var features = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
		assertEquals(8, features.directDependencies().getRadius());
		assertEquals(1, features.blockStateWriteRadius());
		assertEquals(2, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL).getAccumulatedRadiusOf(ChunkStatus.TERRAIN));
		int maximum = 0;
		for (var pyramid : List.of(ChunkPyramid.GENERATION_PYRAMID, ChunkPyramid.LOADING_PYRAMID)) {
			var initializer = pyramid.getStepTo(ChunkStatus.INITIALIZE_LIGHT);
			assertEquals(0, initializer.directDependencies().getRadius());
			assertEquals(-1, initializer.blockStateWriteRadius());
			for (var status : ChunkStatus.getStatusList()) if (status.isOrAfter(ChunkStatus.FEATURES)) {
				var step = pyramid.getStepTo(status);
				int featureSide = 2 * step.getAccumulatedRadiusOf(ChunkStatus.FEATURES) + 1;
				int lightSide = status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT) ? 2 * step.getAccumulatedRadiusOf(ChunkStatus.INITIALIZE_LIGHT) + 1 : 0;
				int total = featureSide * featureSide + lightSide * lightSide;
				assertTrue(total <= FeatureStageDispatcher.MESSAGE_FEATURE_RESERVATION, status.getName());
				maximum = Math.max(maximum, total);
			}
		}
		assertEquals(18, maximum);
		var a = region(0); var touching = region(16); var separated = region(17);
		assertTrue(a.intersects(touching)); assertFalse(a.intersects(separated));
		assertTrue(a.intersects(FeatureStageQueue.Footprint.serial()));
		assertTrue(a.intersects(FeatureStageQueue.Footprint.region(8, 0, 0)));
		assertFalse(a.intersects(FeatureStageQueue.Footprint.region(9, 0, 0)));
		assertTrue(FeatureStageQueue.Footprint.region(Integer.MAX_VALUE, 0, 8).maxX() > Integer.MAX_VALUE);
	}

	@Test void disjointWorkOverlapsWhileConflictingPendingJobsKeepFifo() throws Exception {
		for (int workers : List.of(1,2)) checkDisjointOwnership(workers);
	}
	private void checkDisjointOwnership(int workers) throws Exception {
		var queue = new FeatureStageQueue(workers, 8, "CAWG-Test-Features-");
		var a = new CompletableFuture<String>(); var b = new CompletableFuture<String>();
		var c = new CompletableFuture<String>(); var d = new CompletableFuture<String>();
		var enteredA = new CountDownLatch(1); var enteredB = new CountDownLatch(1);
		var enteredC = new CountDownLatch(1); var enteredD = new CountDownLatch(1);
		var e = new CompletableFuture<String>(); var enteredE = new CountDownLatch(1);
		try {
			var resultA = queue.submitAsync(region(0), () -> { enteredA.countDown(); return a; });
			await(enteredA);
			// Light initialization owns its single chunk through its asynchronous original future.
			var resultB = queue.submitAsync(FeatureStageQueue.Footprint.region(8, 0, 0), () -> { enteredB.countDown(); return b; });
			var resultC = queue.submitAsync(region(40), () -> { enteredC.countDown(); return c; });
			await(enteredC); // Both bodies entered while A's future is still incomplete.
			var resultE = queue.submitAsync(FeatureStageQueue.Footprint.region(80, 0, 0), () -> { enteredE.countDown(); return e; });
			await(enteredE); // Neither incomplete A nor C consumes an idle CPU permit.
			assertEquals(3, queue.snapshot().active()); assertTrue(queue.snapshot().peakExecuting() <= workers);
			var resultD = queue.submitAsync(region(16), () -> { enteredD.countDown(); return d; });
			c.complete("c"); assertEquals("c", resultC.get(5, TimeUnit.SECONDS));
			assertEquals(1, enteredB.getCount()); assertEquals(1, enteredD.getCount());
			assertEquals(2, queue.snapshot().queued()); // D cannot bypass the earlier B it intersects.
			a.complete("a"); assertEquals("a", resultA.get(5, TimeUnit.SECONDS)); await(enteredB);
			assertEquals(1, enteredD.getCount());
			b.complete("b"); assertEquals("b", resultB.get(5, TimeUnit.SECONDS)); await(enteredD);
			d.complete("d"); assertEquals("d", resultD.get(5, TimeUnit.SECONDS));
			e.complete("e"); assertEquals("e", resultE.get(5, TimeUnit.SECONDS));
			assertEquals(3, queue.snapshot().peakActive()); assertTrue(queue.snapshot().bypassed() > 0);
		} finally { a.complete("a"); b.complete("b"); c.complete("c"); d.complete("d"); e.complete("e"); queue.close(); }
	}

	@Test void cancelledObserverKeepsOwnershipAndFailureDoesNotRetryOrExceedCapacity() throws Exception {
		var queue = new FeatureStageQueue(2, 3, "CAWG-Test-Features-");
		var a = new CompletableFuture<Integer>(); var c = new CompletableFuture<Integer>();
		var enteredA = new CountDownLatch(1); var enteredB = new CountDownLatch(1); var enteredC = new CountDownLatch(1);
		var invocations = new AtomicInteger();
		try {
			var observer = queue.submitAsync(region(0), () -> { invocations.incrementAndGet(); enteredA.countDown(); return a; });
			await(enteredA);
			var b = queue.submit(region(0), () -> { enteredB.countDown(); return 2; });
			var other = queue.submitAsync(region(40), () -> { enteredC.countDown(); return c; }); await(enteredC);
			assertTrue(observer.cancel(true)); assertEquals(2, queue.snapshot().active());
			assertEquals(1, enteredB.getCount()); assertFalse(queue.hasCapacity(1));
			assertThrows(RejectedExecutionException.class, () -> queue.submit(region(80), () -> fail("must not start")));
			a.completeExceptionally(new IllegalArgumentException("planned failure"));
			assertEquals(2, b.get(5, TimeUnit.SECONDS)); await(enteredB);
			c.complete(3); assertEquals(3, other.get(5, TimeUnit.SECONDS));
			assertEquals(1, invocations.get()); assertEquals(1, queue.snapshot().failed());
			assertEquals(3, queue.snapshot().peakAdmitted()); assertTrue(observer.isCancelled());
		} finally { a.complete(1); c.complete(3); queue.close(); }
	}

	@Test void messagePumpPausesWithoutBlockingAndResumesOriginalFifo() throws Exception {
		var queue = new FeatureStageQueue(1, 3, "CAWG-Test-Features-");
		var executor = new ManualExecutor(); var order = new ArrayList<Integer>();
		var pump = new FeatureMessagePump(executor, queue, 2, Runnable::run);
		var a = new CompletableFuture<Integer>(); var b = new CompletableFuture<Integer>();
		var enteredA = new CountDownLatch(1); var enteredB = new CountDownLatch(1);
		var results = new ArrayList<CompletableFuture<Integer>>();
		try {
			pump.schedule(() -> {
				order.add(1);
				results.add(queue.submitAsync(region(0), () -> { enteredA.countDown(); return a; }));
				results.add(queue.submitAsync(region(0), () -> { enteredB.countDown(); return b; }));
			});
			pump.schedule(() -> order.add(2)); pump.schedule(() -> order.add(3));
			executor.runNext(); await(enteredA);
			assertEquals(List.of(1), order); assertEquals(2, pump.queued()); assertEquals(0, executor.size());
			assertTrue(pump.pauses() > 0);
			a.complete(1); assertEquals(1, results.get(0).get(5, TimeUnit.SECONDS)); await(enteredB);
			// Completion publishes the result before invoking the wake listener; synchronize on the executor notification.
			executor.awaitTask(); executor.runNext(); executor.runNext(); assertEquals(List.of(1, 2, 3), order);
			b.complete(2); assertEquals(2, results.get(1).get(5, TimeUnit.SECONDS));
		} finally { a.complete(1); b.complete(2); pump.close(); }
	}

	@Test void shutdownDrainsAcceptedBodiesAndPreventsLateMessageExecution() throws Exception {
		var queue = new FeatureStageQueue(1, 3, "CAWG-Test-Features-");
		var a = new CompletableFuture<Integer>(); var enteredA = new CountDownLatch(1);
		var count = new AtomicInteger();
		var drained = new CountDownLatch(2); queue.onCapacityChanged(drained::countDown);
		try {
			var resultA = queue.submitAsync(region(0), () -> { enteredA.countDown(); return a; }); await(enteredA);
			var resultB = queue.submit(region(0), () -> count.incrementAndGet());
			queue.close(); assertThrows(RejectedExecutionException.class, () -> queue.submit(region(40), () -> 0));
			a.complete(2); assertEquals(2, resultA.get(5, TimeUnit.SECONDS)); assertEquals(1, resultB.get(5, TimeUnit.SECONDS));
			await(drained);
			assertEquals(0, queue.snapshot().active()); assertEquals(0, queue.snapshot().queued());
		} finally { a.complete(2); queue.close(); }
		var other = new FeatureStageQueue(1, 3, "CAWG-Test-Features-"); var executor = new ManualExecutor();
		var pump = new FeatureMessagePump(executor, other, 2, Runnable::run);
		pump.schedule(() -> fail("closed queued message must not execute")); pump.close(); executor.runNext();
		assertThrows(RejectedExecutionException.class, () -> pump.schedule(() -> {}));
	}

	@Test void originalStageCompletionRunsBeforeAConflictingBodyCanObserveStatus() throws Exception {
		var queue=new FeatureStageQueue(2,4,"CAWG-Test-StageCommit-");var body=new CompletableFuture<Integer>();
		var entered=new CountDownLatch(1);var committing=new CountDownLatch(1);var release=new CountDownLatch(1);var next=new CountDownLatch(1);
		var status=new AtomicInteger();Thread completion=null;
		try {
			var first=queue.submitAsync(region(0),()->{entered.countDown();return body;});await(entered);
			var originalStep=first.thenApply(value->{committing.countDown();try{await(release);}catch(InterruptedException error){throw new IllegalStateException(error);}status.set(value);return value;});
			var second=queue.submit(region(1),()->{next.countDown();return status.get();});
			completion=new Thread(()->body.complete(7));completion.start();await(committing);
			assertEquals(1,next.getCount());assertEquals(1,queue.snapshot().active());assertEquals(1,queue.snapshot().queued());
			release.countDown();assertEquals(7,originalStep.get(5,TimeUnit.SECONDS));assertEquals(7,second.get(5,TimeUnit.SECONDS));await(next);
		} finally {release.countDown();body.complete(7);if(completion!=null)completion.join(5000);queue.close();}
	}

	@Test void replayKeepsCanonicalAdmissionAndSnapshotsEveryChunkBeforeAnySpawn() {
		assertEquals(882,FeatureFixture263.scope(10).size()); assertEquals(1250,FeatureFixture263.scope(12).size());
		assertEquals(1458,FeatureFixture263.scope(13).size()); assertEquals(31,net.minecraft.server.level.ChunkMap.FORCED_TICKET_LEVEL);
		assertEquals(1682,FeatureFixture263.scope(14).size());
		var a=new FeatureFixtureQueue.Key(0,0);var b=new FeatureFixtureQueue.Key(1,0);var c=new FeatureFixtureQueue.Key(2,0);
		var executor=new ManualExecutor();var admitted=new ArrayList<FeatureStageQueue.Footprint>();var events=new ArrayList<String>();
		var fa=new CompletableFuture<Integer>();var fb=new CompletableFuture<Integer>();var fc=new CompletableFuture<Integer>();
		var completed=new AtomicInteger();
		var fixture=new FeatureFixtureQueue<Integer>(Set.of(a,b,c),Set.of(a,b),Set.of(a,b,c),List.of(a,b,c),executor,2,
			(footprint,body)->{admitted.add(footprint);return body.get();},key->events.add("feature"+key.x()),snapshot->completed.incrementAndGet());
		assertTrue(fixture.awaitingSpawnCompletion());
		var ta=new CompletableFuture<Integer>();var tb=new CompletableFuture<Integer>();var tc=new CompletableFuture<Integer>();
		var terrainA=fixture.terrain(a,()->ta);var terrainB=fixture.terrain(b,()->tb);var terrainC=fixture.terrain(c,()->tc);
		assertThrows(IllegalStateException.class,()->fixture.feature(a,region(0),()->fail("must wait for original terrain bodies")));
		ta.complete(0);tb.complete(1);assertFalse(terrainA.isDone());assertFalse(terrainB.isDone());assertEquals(0,executor.size());
		tc.complete(2);assertEquals(1,executor.size());executor.runNext();assertEquals(2,terrainC.join());
		assertEquals(3,fixture.snapshot().preparedTerrains());assertTrue(fixture.snapshot().terrainsReleased());
		var rc=fixture.feature(c,region(2),()->fc);fixture.feature(b,region(1),()->fb);assertEquals(0,executor.size());
		var ra=fixture.feature(a,region(0),()->fa);assertTrue(ra.cancel(true));executor.runNext();
		assertEquals(List.of(region(0),region(1)),admitted);assertEquals(1,executor.size());executor.runNext();
		assertEquals(List.of("feature0","feature1","feature2"),events);
		var initializationA=fixture.initialization(a,region(0),()->{events.add("init0");return CompletableFuture.completedFuture(0);});
		var initializationB=fixture.initialization(b,region(1),()->{events.add("init1");return CompletableFuture.completedFuture(1);});
		assertEquals(0,executor.size());assertFalse(initializationA.isDone());
		var sa=fixture.spawn(a,()->events.add("snapshot0"),()->{events.add("spawn0");return CompletableFuture.completedFuture(10);});
		var sb=fixture.spawn(b,()->events.add("snapshot1"),()->{events.add("spawn1");return CompletableFuture.completedFuture(11);});
		assertTrue(sa.cancel(false));fa.complete(0);fb.complete(1);assertEquals(0,executor.size());
		fc.complete(2);assertEquals(2,rc.join());assertEquals(2,executor.size());executor.runNext();executor.runNext();
		assertEquals(1,initializationB.join());assertEquals(0,executor.size());assertFalse(fixture.snapshotCaptured(a));
		// Late initialization admission after the last feature schedules exactly once.
		var initializationC=fixture.initialization(c,region(2),()->{events.add("init2");return CompletableFuture.completedFuture(2);});
		executor.runNext();assertEquals(2,initializationC.join());assertEquals(1,executor.size());executor.runNext();
		assertEquals(List.of("feature0","feature1","feature2","init0","init1","init2","snapshot0","snapshot1"),events);
		assertTrue(fixture.snapshotCaptured(a));assertTrue(fixture.snapshotCaptured(b));assertEquals(2,executor.size());
		assertTrue(fixture.awaitingSpawnCompletion());
		executor.runNext();executor.runNext();assertEquals(11,sb.join());assertEquals(1,completed.get());
		assertEquals(3,fixture.snapshot().finishedFeatures());assertEquals(2,fixture.snapshot().completedSpawns());
		assertFalse(fixture.awaitingSpawnCompletion());
		fixture.close();assertTrue(fixture.snapshot().closed());assertFalse(fixture.snapshot().failed());
	}

	@Test void fixtureRejectsIncompleteReplayAndStopsUnstartedBodiesAfterFailure() {
		var a=new FeatureFixtureQueue.Key(0,0);var b=new FeatureFixtureQueue.Key(1,0);var executor=new ManualExecutor();
		assertThrows(IllegalArgumentException.class,()->new FeatureFixtureQueue<Integer>(Set.of(a,b),Set.of(a),List.of(a,a),executor,2,
			(footprint,body)->body.get(),key->{},snapshot->{}));
		var calls=new AtomicInteger();
		var fixture=new FeatureFixtureQueue<Integer>(Set.of(a,b),Set.of(a),List.of(a,b),executor,2,
			(footprint,body)->body.get(),key->{},snapshot->fail("failure must not complete"));
		var rb=fixture.feature(b,region(1),()->{calls.incrementAndGet();return CompletableFuture.completedFuture(1);});
		var ra=fixture.feature(a,region(0),()->{calls.incrementAndGet();throw new IllegalStateException("partial original mutation");});
		executor.runNext();assertEquals(1,calls.get());assertTrue(ra.isCompletedExceptionally());assertTrue(rb.isCompletedExceptionally());
		assertTrue(fixture.snapshot().failed());assertEquals(0,fixture.snapshot().waitingFeatures());
		assertFalse(fixture.awaitingSpawnCompletion());
		assertThrows(RejectedExecutionException.class,()->fixture.spawn(a,()->{},()->CompletableFuture.completedFuture(1)));fixture.close();
		var original=new FeatureFixtureQueue<Integer>(Set.of(a),Set.of(a),null,executor,1,
			(footprint,body)->body.get(),key->{},snapshot->{});
		var pending=new CompletableFuture<Integer>();var observer=original.feature(a,region(0),()->pending);
		assertThrows(IllegalArgumentException.class,()->original.feature(a,region(0),()->fail("duplicate")));
		original.close();assertTrue(observer.isCompletedExceptionally());assertTrue(original.snapshot().failed());
		assertFalse(original.awaitingSpawnCompletion());
		pending.complete(1);assertEquals(0,executor.size()); // Accepted body completes; no SPAWN or retry after close.
	}

	private static FeatureStageQueue.Footprint region(int x) { return FeatureStageQueue.Footprint.region(x, 0, 8); }
	private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
	private static final class ManualExecutor implements java.util.concurrent.Executor {
		final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		public synchronized void execute(Runnable task) { tasks.addLast(task); notifyAll(); }
		synchronized int size() { return tasks.size(); }
		void runNext() { Runnable task; synchronized (this) { task = tasks.removeFirst(); } task.run(); }
		synchronized void awaitTask() throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (tasks.isEmpty()) {
				long remaining = deadline-System.nanoTime(); assertTrue(remaining > 0); TimeUnit.NANOSECONDS.timedWait(this, remaining);
			}
		}
	}
}
