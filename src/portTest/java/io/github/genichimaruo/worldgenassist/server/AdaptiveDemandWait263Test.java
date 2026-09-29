package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdaptiveDemandWait263Test {
	@Test void acceptsUsefulReplyAfterOldDeadlineAndAccountsForPrefetchAge() {
		var scheduler = new ManualScheduler();
		var policy = new AdaptiveDemandWait(scheduler, () -> scheduler.now);
		UUID owner = UUID.randomUUID();
		var prepared = new CompletableFuture<Integer>();
		var waiting = policy.await(owner, 0, 100, true, prepared, () -> false,
			() -> fail("Useful response was prematurely cancelled"));
		scheduler.advance(100); assertFalse(waiting.isDone());
		scheduler.advance(140); prepared.complete(7);
		assertEquals(7, waiting.join()); assertEquals(0, scheduler.pending());
		assertEquals(100, policy.initialMillis(owner, 0, 100, true));
	}

	@Test void grantsOneGraceOnlyForDecodedResponseAndRejectsLateCompletion() {
		var scheduler = new ManualScheduler();
		var policy = new AdaptiveDemandWait(scheduler, () -> scheduler.now);
		var prepared = new CompletableFuture<Integer>();
		var decoded = new AtomicBoolean(true);
		var expired = new AtomicInteger();
		var waiting = policy.await(UUID.randomUUID(), 0, 100, true, prepared, decoded::get,
			() -> { expired.incrementAndGet(); prepared.cancel(false); });
		scheduler.advance(150); assertFalse(waiting.isDone());
		scheduler.advance(175);
		assertInstanceOf(TimeoutException.class, assertThrows(CompletionException.class, waiting::join).getCause());
		assertFalse(prepared.complete(7)); assertEquals(1, expired.get());
		scheduler.advance(500); assertEquals(1, expired.get()); assertEquals(0, scheduler.pending());
		var absent = new CompletableFuture<Integer>();
		var noGrace = policy.await(UUID.randomUUID(), scheduler.now, 100, true, absent, () -> false, expired::incrementAndGet);
		scheduler.advance(650); assertTrue(noGrace.isCompletedExceptionally()); assertEquals(2, expired.get());
	}

	@Test void isolatesOwnerLearningAndBoundsOrSkipsUnlikelyDirectWork() {
		var scheduler = new ManualScheduler();
		var policy = new AdaptiveDemandWait(scheduler, () -> scheduler.now);
		UUID slow = UUID.randomUUID(), fast = UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			policy.recordReady(slow, TimeUnit.MILLISECONDS.toNanos(300));
			policy.recordReady(fast, TimeUnit.MILLISECONDS.toNanos(90));
		}
		assertFalse(policy.shouldSubmitDirect(slow, 100, true)); assertTrue(policy.shouldSubmitDirect(fast, 100, true));
		assertTrue(policy.shouldSubmitDirect(slow, 100, false));
		assertEquals(200, policy.initialMillis(slow, 0, 100, true));
		assertEquals(115, policy.initialMillis(fast, 0, 100, true));
		assertEquals(100, policy.initialMillis(slow, 0, 100, false));
		assertEquals(30_000, policy.initialMillis(slow, 0, 30_000, false));
		assertEquals(10, policy.initialMillis(slow, 0, 5, true));
		assertEquals(1000, policy.initialMillis(slow, 0, 1000, true));
		policy.removeOwner(slow); assertTrue(policy.shouldSubmitDirect(slow, 100, true));
		assertEquals(150, policy.initialMillis(slow, 0, 100, true));
		policy.clear(); assertEquals(150, policy.initialMillis(fast, 0, 100, true));
	}

	@Test void hardCeilingAndFixedControlNeverExtend() {
		var scheduler = new ManualScheduler();
		var policy = new AdaptiveDemandWait(scheduler, () -> scheduler.now);
		UUID owner = UUID.randomUUID();
		policy.recordReady(owner, TimeUnit.MILLISECONDS.toNanos(250));
		var prepared = new CompletableFuture<Integer>();
		var expired = new AtomicInteger();
		var capped = policy.await(owner, 0, 100, true, prepared, () -> true, expired::incrementAndGet);
		scheduler.advance(199); assertFalse(capped.isDone());
		scheduler.advance(200); assertTrue(capped.isCompletedExceptionally()); assertEquals(1, expired.get());
		var fixed = policy.await(owner, scheduler.now, 100, false, new CompletableFuture<Integer>(), () -> true, expired::incrementAndGet);
		scheduler.advance(300); assertTrue(fixed.isCompletedExceptionally()); assertEquals(2, expired.get());
		var late = new CompletableFuture<Integer>();
		var delayedTimer = policy.await(owner, scheduler.now, 100, true, late, () -> true, expired::incrementAndGet);
		// A busy timer executor must not let a response bypass the absolute ceiling.
		scheduler.now = TimeUnit.MILLISECONDS.toNanos(501);
		late.complete(9);
		assertTrue(delayedTimer.isCompletedExceptionally()); assertEquals(3, expired.get());
		scheduler.advance(550); assertEquals(3, expired.get());
	}

	@Test void failureAndCancellationReleaseTimerAndDoNotApplyAResponse() {
		var scheduler = new ManualScheduler();
		var policy = new AdaptiveDemandWait(scheduler, () -> scheduler.now);
		var expired = new AtomicInteger();
		UUID owner = UUID.randomUUID();
		var prepared = new CompletableFuture<Integer>();
		var waiting = policy.await(owner, 0, 100, true, prepared, () -> true, expired::incrementAndGet);
		prepared.completeExceptionally(new IllegalStateException("Invalid context"));
		assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, waiting::join).getCause());
		assertEquals(0, scheduler.pending());
		var next = new CompletableFuture<Integer>();
		var cancelled = policy.await(owner, 0, 100, true, next, () -> false, expired::incrementAndGet);
		cancelled.cancel(false); assertTrue(next.isCancelled()); assertEquals(0, scheduler.pending());
		scheduler.advance(500); assertEquals(0, expired.get());
		var ready = CompletableFuture.completedFuture(8);
		assertSame(ready, policy.await(owner, 0, 100, true, ready, () -> false, expired::incrementAndGet));
	}

	/** Virtual time covers expiry races and cleanup without sleeps or game startup. */
	private static final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
		long now;
		private boolean stopped;
		private final PriorityQueue<Task<?>> queue = new PriorityQueue<>();
		void advance(long millis) {
			long target = TimeUnit.MILLISECONDS.toNanos(millis);
			while (!queue.isEmpty() && queue.peek().due <= target) {
				var task = queue.remove(); now = task.due; task.run();
			}
			now = target;
		}
		int pending() { return (int) queue.stream().filter(task -> !task.isDone()).count(); }
		@Override public <V> ScheduledFuture<V> schedule(Callable<V> task, long delay, TimeUnit unit) {
			if (stopped) throw new RejectedExecutionException();
			var future = new Task<>(task, now + unit.toNanos(delay)); queue.add(future); return future;
		}
		@Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
			return schedule(Executors.callable(task), delay, unit);
		}
		@Override public void execute(Runnable task) { schedule(task, 0, TimeUnit.NANOSECONDS); }
		@Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long start, long period, TimeUnit unit) { throw new UnsupportedOperationException(); }
		@Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long start, long period, TimeUnit unit) { throw new UnsupportedOperationException(); }
		@Override public void shutdown() { stopped = true; }
		@Override public List<Runnable> shutdownNow() { stopped = true; queue.clear(); return List.of(); }
		@Override public boolean isShutdown() { return stopped; }
		@Override public boolean isTerminated() { return stopped; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
		private final class Task<V> extends FutureTask<V> implements ScheduledFuture<V> {
			final long due;
			Task(Callable<V> callable, long due) { super(callable); this.due = due; }
			@Override public long getDelay(TimeUnit unit) { return unit.convert(due - now, TimeUnit.NANOSECONDS); }
			@Override public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS)); }
		}
	}
}
