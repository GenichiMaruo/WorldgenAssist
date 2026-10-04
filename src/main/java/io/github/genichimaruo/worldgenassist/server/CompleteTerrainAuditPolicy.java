package io.github.genichimaruo.worldgenassist.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;

/** A separately opted-in trusted-friend policy; never substitutes for old cell validation. */
public final class CompleteTerrainAuditPolicy<K> {
	public static final int REQUIRED_INITIAL_SUCCESSES = 2;
	public static final int AUDIT_DENOMINATOR = 8;
	private final int capacity;
	private final IntSupplier privateRandom;
	private final Map<K, State> contexts = new HashMap<>();
	private final Map<UUID, Token<K>> pending = new HashMap<>();

	/** privateRandom must return a server-private uniform integer in [0,8). */
	public CompleteTerrainAuditPolicy(int capacity, IntSupplier privateRandom) {
		if (capacity < 1 || capacity > 64) throw new IllegalArgumentException("Invalid audit capacity");
		this.capacity = capacity; this.privateRandom = Objects.requireNonNull(privateRandom);
	}

	/** Called after normal job admission. K must contain owner, epoch and complete context. */
	public synchronized Token<K> select(K context, UUID jobId) {
		Objects.requireNonNull(context); Objects.requireNonNull(jobId);
		if (!canAdmit(context) || pending.containsKey(jobId)) throw new IllegalStateException("Audit admission full or duplicate");
		State state = contexts.get(context);
		if (state == null) {
			if (contexts.size() >= 64) throw new IllegalStateException("Audit context limit");
			state = new State(); contexts.put(context, state);
		}
		boolean audit = state.successes < REQUIRED_INITIAL_SUCCESSES;
		if (!audit) {
			int choice = privateRandom.getAsInt();
			if (choice < 0 || choice >= AUDIT_DENOMINATOR) throw new IllegalStateException("Invalid private audit draw");
			audit = choice == 0;
		}
		Token<K> token = new Token<>(context, jobId, state, audit);
		pending.put(jobId, token);
		return token;
	}
	/** Prevent a cold owner from filling the audit queue before its first two results agree. */
	public synchronized boolean canAdmit(K context) {
		if (pending.size() >= capacity) return false;
		State state = contexts.get(context);
		if (state == null) return contexts.size() < 64;
		if (state.successes >= REQUIRED_INITIAL_SUCCESSES) return true;
		long initialPending = pending.values().stream().filter(token -> token.state == state).count();
		return initialPending < REQUIRED_INITIAL_SUCCESSES - state.successes;
	}

	/** Only server validation calls this, after the independent comparison has succeeded. */
	public synchronized boolean accept(Token<K> token) {
		if (!current(token)) return false;
		pending.remove(token.jobId);
		if (token.audit) token.state.successes = Math.min(REQUIRED_INITIAL_SUCCESSES, token.state.successes + 1);
		return true;
	}
	public synchronized void cancel(Token<K> token) {
		if (current(token)) pending.remove(token.jobId);
	}
	public synchronized boolean current(Token<K> token) {
		return token != null && pending.get(token.jobId) == token && contexts.get(token.context) == token.state;
	}
	public synchronized void invalidate(K context) {
		State removed = contexts.remove(context);
		if (removed != null) pending.values().removeIf(token -> token.state == removed);
	}
	public synchronized void invalidateMatching(java.util.function.Predicate<K> predicate) {
		contexts.keySet().removeIf(predicate);
		pending.values().removeIf(token -> contexts.get(token.context) != token.state);
	}
	public synchronized void clear() { pending.clear(); contexts.clear(); }
	public synchronized int pendingCount() { return pending.size(); }
	private static final class State { int successes; }
	public static final class Token<K> {
		private final K context;
		private final UUID jobId;
		private final State state;
		private final boolean audit;
		private Token(K context, UUID jobId, State state, boolean audit) {
			this.context = context; this.jobId = jobId; this.state = state; this.audit = audit;
		}
		public boolean requiresFullAudit() { return audit; }
	}
}
