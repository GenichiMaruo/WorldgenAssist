package io.github.genichimaruo.worldgenassist.server;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Admission covers remote wait and preparation, including a cancelled running invocation. */
final class BoundedRemotePreparation<P, R> {
	private final int capacity;
	private final Executor executor;
	private final Set<Ticket> tickets = new HashSet<>();

	BoundedRemotePreparation(int capacity, Executor executor) {
		if (capacity < 1) throw new IllegalArgumentException("Invalid capacity");
		this.capacity = capacity; this.executor = executor;
	}

	synchronized Ticket reserve(UUID owner, int ownerLimit) {
		if (tickets.size() >= capacity || tickets.stream().filter(t -> t.owner.equals(owner)).count() >= ownerLimit)
			return null;
		Ticket ticket = new Ticket(owner);
		tickets.add(ticket);
		return ticket;
	}

	synchronized int size() { return tickets.size(); }
	void cancelOwner(UUID owner) {
		for (Ticket ticket : snapshot()) if (ticket.owner.equals(owner)) ticket.cancel();
	}
	void cancelAll() { for (Ticket ticket : snapshot()) ticket.cancel(); }
	private synchronized Set<Ticket> snapshot() { return Set.copyOf(tickets); }
	private synchronized void release(Ticket ticket) { tickets.remove(ticket); }

	final class Ticket {
		private final UUID owner;
		private final CompletableFuture<P> prepared = new CompletableFuture<>();
		private final CompletableFuture<R> result = new CompletableFuture<>();
		private FutureTask<Void> task;
		private boolean started;
		private boolean running;
		private boolean preparationExited;
		private boolean released;

		private Ticket(UUID owner) {
			this.owner = owner;
			result.whenComplete((value, error) -> {
				if (error != null) cancelTask();
				releaseIfFinished();
			});
		}

		CompletableFuture<R> start(Supplier<P> prepare, CompletableFuture<R> remote, BiFunction<R, P, R> compare) {
			synchronized (this) {
				if (started || result.isDone()) return result;
				started = true;
				task = new FutureTask<>(() -> {
					try { prepared.complete(prepare.get()); }
					catch (Throwable error) { prepared.completeExceptionally(error); }
					return null;
				}) {
					@Override public void run() {
						synchronized (Ticket.this) { running = true; }
						try { super.run(); }
						finally {
							synchronized (Ticket.this) { running = false; preparationExited = true; }
							releaseIfFinished();
						}
					}
					@Override protected void done() {
						synchronized (Ticket.this) { if (!running) preparationExited = true; }
						if (isCancelled()) prepared.completeExceptionally(new CancellationException());
						releaseIfFinished();
					}
				};
			}
			combine(remote, compare);
			try { executor.execute(task); }
			catch (RuntimeException error) { result.completeExceptionally(error); }
			return result;
		}

		/** Constant preparation can bypass CPU work, but admission covers the entire remote wait. */
		CompletableFuture<R> startPrepared(P value, CompletableFuture<R> remote, BiFunction<R, P, R> compare) {
			synchronized (this) {
				if (started || result.isDone()) return result;
				started = true;
				preparationExited = true;
			}
			prepared.complete(value);
			combine(remote, compare);
			return result;
		}

		private void combine(CompletableFuture<R> remote, BiFunction<R, P, R> compare) {
			// Fail promptly; thenCombine alone waits for the other input on failure.
			remote.whenComplete((value, error) -> { if (error != null) result.completeExceptionally(error); });
			prepared.whenComplete((value, error) -> { if (error != null) result.completeExceptionally(error); });
			remote.thenCombine(prepared, (value, sample) -> {
				if (result.isDone()) throw new CancellationException();
				return compare.apply(value, sample);
			}).whenComplete((value, error) -> {
				if (error == null) result.complete(value); else result.completeExceptionally(error);
			});
		}

		void cancel() { result.completeExceptionally(new CancellationException("Remote preparation invalidated")); }
		private void cancelTask() {
			FutureTask<Void> current;
			synchronized (this) {
				current = task;
				if (current == null) preparationExited = true;
			}
			if (current != null) current.cancel(true);
		}
		private void releaseIfFinished() {
			boolean shouldRelease;
			synchronized (this) {
				shouldRelease = !released && preparationExited && result.isDone();
				if (shouldRelease) released = true;
			}
			if (shouldRelease) release(this);
		}
	}
}
