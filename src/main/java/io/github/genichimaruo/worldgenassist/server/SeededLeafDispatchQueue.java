package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BiConsumer;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;

/** Bounded owner-specific transfers and best-effort cancellations, drained by the server thread. */
public final class SeededLeafDispatchQueue implements SeededLeafJobOrchestrator.ClientExchange, AutoCloseable {
	private final SeededLeafJobOrchestrator orchestrator;
	private final ConcurrentHashMap<SeededLeafJobOrchestrator.Connection, Transfer> pending = new ConcurrentHashMap<>();
	private final ArrayBlockingQueue<Cancellation> cancellations;
	private final int capacity;
	private final AtomicBoolean closed = new AtomicBoolean();
	public SeededLeafDispatchQueue(SeededLeafJobOrchestrator orchestrator) {
		this(orchestrator, 1);
	}
	public SeededLeafDispatchQueue(SeededLeafJobOrchestrator orchestrator, int capacity) {
		this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
		if (capacity < 1 || capacity > 64) { throw new IllegalArgumentException("Invalid dispatch capacity"); }
		this.capacity = capacity;
		cancellations = new ArrayBlockingQueue<>(capacity);
	}
	@Override public synchronized CompletionStage<SeededLeafDensityResultEnvelope> send(
		SeededLeafJobOrchestrator.Connection owner, AuthorizedSeededLeafJob job
	) {
		Transfer transfer = new Transfer(owner, job);
		if (closed.get() || pending.size() >= capacity || pending.putIfAbsent(owner, transfer) != null) {
			throw new RejectedExecutionException("Fixture dispatch queue unavailable");
		}
		if (closed.get()) { abandon(transfer); }
		return transfer.response.minimalCompletionStage();
	}
	@Override public void cancel(SeededLeafJobOrchestrator.Connection owner, SeededLeafJobClaim claim) {
		Transfer transfer = pending.get(owner);
		if (transfer != null && transfer.owner == owner && transfer.claim.equals(claim)
			&& pending.remove(owner, transfer)) {
			transfer.authorization = null;
			if (transfer.sent.get()) { cancellations.offer(new Cancellation(owner, claim)); }
			transfer.response.completeExceptionally(new CancellationException("Fixture attempt cancelled"));
		}
	}
	public void drain(
		BiConsumer<SeededLeafJobOrchestrator.Connection, AuthorizedSeededLeafJob> requestSender,
		BiConsumer<SeededLeafJobOrchestrator.Connection, SeededLeafJobClaim> cancelSender
	) {
		for (int index = 0; index < capacity; index++) {
			Cancellation cancelled = cancellations.poll();
			if (cancelled == null) { break; }
			try { cancelSender.accept(cancelled.owner, cancelled.claim); }
			catch (RuntimeException ignored) { /* Best effort; local fallback has already won. */ }
		}
		for (Transfer transfer : List.copyOf(pending.values())) {
		if (!transfer.sent.compareAndSet(false, true)) { continue; }
		AuthorizedSeededLeafJob authorization = transfer.authorization;
		try {
			if (authorization == null || !orchestrator.dispatchIfCurrent(transfer.owner, transfer.claim, () -> {
				if (closed.get() || pending.get(transfer.owner) != transfer) { throw new CancellationException(); }
				requestSender.accept(transfer.owner, authorization);
			})) { abandon(transfer); }
		} catch (RuntimeException exception) {
			if (pending.remove(transfer.owner, transfer)) { transfer.response.completeExceptionally(exception); }
		} finally {
			transfer.authorization = null;
		}
		}
	}
	public boolean receive(SeededLeafJobOrchestrator.Connection owner, SeededLeafDensityResultEnvelope result) {
		Transfer transfer = pending.get(owner);
		if (transfer == null || transfer.owner != owner || !transfer.sent.get()
			|| !transfer.claim.jobId().equals(result.claim().jobId()) || !pending.remove(owner, transfer)) { return false; }
		transfer.authorization = null;
		transfer.response.complete(result);
		return true;
	}
	public boolean fail(SeededLeafJobOrchestrator.Connection owner, SeededLeafJobClaim claim) {
		Transfer transfer = pending.get(owner);
		if (transfer == null || transfer.owner != owner || !transfer.claim.equals(claim)) { return false; }
		return abandon(transfer);
	}
	public int pendingCount() { return pending.size(); }
	private boolean abandon(Transfer transfer) {
		if (!pending.remove(transfer.owner, transfer)) { return false; }
		transfer.authorization = null;
		transfer.response.completeExceptionally(new CancellationException("Fixture exchange unavailable"));
		return true;
	}
	@Override public void close() {
		closed.set(true);
		for (Transfer transfer : List.copyOf(pending.values())) { abandon(transfer); }
		cancellations.clear();
	}
	private record Cancellation(SeededLeafJobOrchestrator.Connection owner, SeededLeafJobClaim claim) { }
	private static final class Transfer {
		private final SeededLeafJobOrchestrator.Connection owner;
		private final SeededLeafJobClaim claim;
		private final CompletableFuture<SeededLeafDensityResultEnvelope> response = new CompletableFuture<>();
		private final AtomicBoolean sent = new AtomicBoolean();
		private volatile AuthorizedSeededLeafJob authorization;
		private Transfer(SeededLeafJobOrchestrator.Connection owner, AuthorizedSeededLeafJob authorization) {
			this.owner = Objects.requireNonNull(owner, "owner");
			this.authorization = Objects.requireNonNull(authorization, "authorization");
			claim = SeededLeafJobClaim.fromAuthorization(authorization);
		}
	}
}
