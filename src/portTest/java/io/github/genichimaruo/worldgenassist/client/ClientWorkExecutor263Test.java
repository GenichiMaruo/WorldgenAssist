package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class ClientWorkExecutor263Test {
	@Test @Timeout(10) void wideWindowRemainsBoundedAcrossResultHandoffAndCancellation() throws Exception {
		var executor = new ClientWorkExecutor(2,16,Thread::new);
		var running = new CountDownLatch(2); var release = new CountDownLatch(1);
		try {
			for(int i=0;i<2;i++) executor.execute(() -> {running.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
			assertTrue(running.await(2,TimeUnit.SECONDS)); assertEquals(16,executor.advertisedJobs());
			var queued=new java.util.ArrayList<Future<?>>();
			for(int i=0;i<16;i++) queued.add(executor.submit(()->{}));
			assertEquals(16,executor.getQueue().size());
			assertThrows(RejectedExecutionException.class,()->executor.execute(()->{}));
			for(var task:queued)task.cancel(false);
			executor.purge();assertEquals(0,executor.getQueue().size());
			var replacement=executor.submit(()->42);release.countDown();assertEquals(42,replacement.get(2,TimeUnit.SECONDS));
		}finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
	}
	@Test void rejectsUnboundedWindow() {
		assertThrows(IllegalArgumentException.class,()->new ClientWorkExecutor(2,0,Thread::new));
		assertThrows(IllegalArgumentException.class,()->new ClientWorkExecutor(2,65,Thread::new));
	}
	@Test @Timeout(10) void sentResultsCanHandOffTheAdvertisedWindowWithoutUnboundedAdmission() throws Exception {
		var executor=new ClientWorkExecutor(2,Thread::new);
		var sending=new CountDownLatch(2); var returned=new CountDownLatch(1); var computed=new AtomicInteger();
		try {
			for(int i=0;i<2;i++)executor.execute(() -> { sending.countDown(); try{returned.await();}catch(InterruptedException e){Thread.currentThread().interrupt();} });
			assertTrue(sending.await(2,TimeUnit.SECONDS));
			var fresh=new java.util.ArrayList<Future<?>>();
			for(int i=0;i<executor.advertisedJobs();i++)fresh.add(executor.submit(computed::incrementAndGet));
			assertEquals(4,executor.getQueue().size());
			assertThrows(RejectedExecutionException.class,() -> executor.submit(computed::incrementAndGet));
			var cancelled=fresh.removeLast(); assertTrue(cancelled.cancel(true)); assertTrue(executor.remove((Runnable)cancelled));
			assertEquals(3,executor.getQueue().size());
			returned.countDown(); for(var task:fresh)task.get(2,TimeUnit.SECONDS);
			assertEquals(3,computed.get());
		} finally { returned.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS)); }
	}
}
