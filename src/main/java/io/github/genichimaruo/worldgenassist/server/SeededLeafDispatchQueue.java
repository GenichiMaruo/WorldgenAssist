package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;

/** One transfer plus one best-effort cancellation, drained by the server thread. */
public final class SeededLeafDispatchQueue implements SeededLeafJobOrchestrator.ClientExchange, AutoCloseable {
	private final SeededLeafJobOrchestrator orchestrator;
	private final AtomicReference<Transfer> pending = new AtomicReference<>();
	private final AtomicReference<Cancellation> cancellation = new AtomicReference<>();
	private final AtomicBoolean closed = new AtomicBoolean();
	public SeededLeafDispatchQueue(SeededLeafJobOrchestrator orchestrator) {
		this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
	}
	@Override public CompletionStage<SeededLeafDensityResultEnvelope> send(
		SeededLeafJobOrchestrator.Connection owner, AuthorizedSeededLeafJob job
	) {
		Transfer transfer = new Transfer(owner, job);
		if (closed.get() || !pending.compareAndSet(null, transfer)) {
			throw new RejectedExecutionException("Fixture dispatch queue unavailable");
		}
		if (closed.get()) { abandon(transfer); }
		return transfer.response.minimalCompletionStage();
	}
	@Override public void cancel(SeededLeafJobOrchestrator.Connection owner, SeededLeafJobClaim claim) {
		Transfer transfer = pending.get();
		if (transfer != null && transfer.owner == owner && transfer.claim.equals(claim)
			&& pending.compareAndSet(transfer, null)) {
			transfer.authorization = null;
			if (transfer.sent.get()) { cancellation.set(new Cancellation(owner, claim)); }
			transfer.response.completeExceptionally(new CancellationException("Fixture attempt cancelled"));
		}
	}
	public void drain(
		BiConsumer<SeededLeafJobOrchestrator.Connection, AuthorizedSeededLeafJob> requestSender,
		BiConsumer<SeededLeafJobOrchestrator.Connection, SeededLeafJobClaim> cancelSender
	) {
		Cancellation cancelled = cancellation.getAndSet(null);
		if (cancelled != null) {
			try { cancelSender.accept(cancelled.owner, cancelled.claim); }
			catch (RuntimeException ignored) { /* Best effort; local fallback has already won. */ }
		}
		Transfer transfer = pending.get();
		if (transfer == null || !transfer.sent.compareAndSet(false, true)) { return; }
		AuthorizedSeededLeafJob authorization = transfer.authorization;
		try {
			if (authorization == null || !orchestrator.dispatchIfCurrent(transfer.owner, transfer.claim, () -> {
				if (closed.get() || pending.get() != transfer) { throw new CancellationException(); }
				requestSender.accept(transfer.owner, authorization);
			})) { abandon(transfer); }
		} catch (RuntimeException exception) {
			if (pending.compareAndSet(transfer, null)) { transfer.response.completeExceptionally(exception); }
		} finally {
			transfer.authorization = null;
		}
	}
	public boolean receive(SeededLeafJobOrchestrator.Connection owner, SeededLeafDensityResultEnvelope result) {
		Transfer transfer = pending.get();
		if (transfer == null || transfer.owner != owner || !transfer.sent.get()
			|| !transfer.claim.jobId().equals(result.claim().jobId()) || !pending.compareAndSet(transfer, null)) { return false; }
		transfer.authorization = null;
		transfer.response.complete(result);
		return true;
	}
	public boolean fail(SeededLeafJobOrchestrator.Connection owner, SeededLeafJobClaim claim) {
		Transfer transfer = pending.get();
		if (transfer == null || transfer.owner != owner || !transfer.claim.equals(claim)) { return false; }
		return abandon(transfer);
	}
	public int pendingCount() { return pending.get() == null ? 0 : 1; }
	private boolean abandon(Transfer transfer) {
		if (!pending.compareAndSet(transfer, null)) { return false; }
		transfer.authorization = null;
		transfer.response.completeExceptionally(new CancellationException("Fixture exchange unavailable"));
		return true;
	}
	@Override public void close() {
		closed.set(true);
		Transfer transfer = pending.get();
		if (transfer != null) { abandon(transfer); }
		cancellation.set(null);
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
