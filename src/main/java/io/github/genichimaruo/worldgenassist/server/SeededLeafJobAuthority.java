package io.github.genichimaruo.worldgenassist.server;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobSpec;

/**
 * Server-owned lifecycle, admission, disclosure budget, and one-shot claim state
 * for the unregistered seeded-leaf protocol draft.
 */
public final class SeededLeafJobAuthority {
	public static final int MAX_CONFIGURED_JOBS = 65_536;
	public static final int MAX_DISCLOSED_ENTRIES_PER_OWNER = 100_000_000;
	public static final int MAX_PENDING_TRANSCRIPT_ENTRIES = 100_000;
	public static final int MAX_TRACKED_DISCLOSURE_OWNERS = 1_024;
	private static final int MAX_JOB_ID_ATTEMPTS = 32;

	private final int maxInFlightTotal;
	private final int maxInFlightPerOwner;
	private final int maxPendingTranscriptEntries;
	private final int maxDisclosedEntriesPerOwner;
	private final long jobTimeoutNanos;
	private final long terminalRetentionNanos;
	private final LongSupplier nanoTime;
	private final Supplier<UUID> jobIdSupplier;
	private final Supplier<OpaqueWorldgenContextId> contextIdSupplier;
	private final Supplier<SeededLeafJobAuthenticator> authenticatorSupplier;
	private final SeededLeafGlobalDisclosureBudget globalDisclosureBudget;
	private final Map<UUID, Entry> entries = new LinkedHashMap<>();
	private final Map<UUID, Integer> pendingByOwner = new HashMap<>();
	private final Map<UUID, Integer> disclosedEntriesByOwner = new HashMap<>();

	private Session session;
	private long contextGeneration;
	private int pendingCount;
	private int pendingTranscriptEntries;

	SeededLeafJobAuthority(
		int maxInFlightTotal,
		int maxInFlightPerOwner,
		int maxDisclosedEntriesPerOwner,
		Duration jobTimeout,
		Duration terminalRetention
	) {
		this(
			maxInFlightTotal,
			maxInFlightPerOwner,
			MAX_PENDING_TRANSCRIPT_ENTRIES,
			maxDisclosedEntriesPerOwner,
			jobTimeout,
			terminalRetention,
			System::nanoTime,
			UUID::randomUUID,
			new SecureRandom()
		);
	}

	private SeededLeafJobAuthority(
		int maxInFlightTotal,
		int maxInFlightPerOwner,
		int maxPendingTranscriptEntries,
		int maxDisclosedEntriesPerOwner,
		Duration jobTimeout,
		Duration terminalRetention,
		LongSupplier nanoTime,
		Supplier<UUID> jobIdSupplier,
		SecureRandom random
	) {
		this(
			maxInFlightTotal,
			maxInFlightPerOwner,
			maxPendingTranscriptEntries,
			maxDisclosedEntriesPerOwner,
			jobTimeout,
			terminalRetention,
			nanoTime,
			jobIdSupplier,
			() -> OpaqueWorldgenContextId.random(random),
			() -> SeededLeafJobAuthenticator.random(random)
		);
	}

	SeededLeafJobAuthority(
		int maxInFlightTotal,
		int maxInFlightPerOwner,
		int maxPendingTranscriptEntries,
		int maxDisclosedEntriesPerOwner,
		Duration jobTimeout,
		Duration terminalRetention,
		LongSupplier nanoTime,
		Supplier<UUID> jobIdSupplier,
		Supplier<OpaqueWorldgenContextId> contextIdSupplier,
		Supplier<SeededLeafJobAuthenticator> authenticatorSupplier
	) {
		this(
			maxInFlightTotal,
			maxInFlightPerOwner,
			maxPendingTranscriptEntries,
			maxDisclosedEntriesPerOwner,
			jobTimeout,
			terminalRetention,
			nanoTime,
			jobIdSupplier,
			contextIdSupplier,
			authenticatorSupplier,
			new SeededLeafVolatileDisclosureBudget(MAX_DISCLOSED_ENTRIES_PER_OWNER)
		);
	}

	SeededLeafJobAuthority(
		int maxInFlightTotal,
		int maxInFlightPerOwner,
		int maxPendingTranscriptEntries,
		int maxDisclosedEntriesPerOwner,
		Duration jobTimeout,
		Duration terminalRetention,
		LongSupplier nanoTime,
		Supplier<UUID> jobIdSupplier,
		Supplier<OpaqueWorldgenContextId> contextIdSupplier,
		Supplier<SeededLeafJobAuthenticator> authenticatorSupplier,
		SeededLeafGlobalDisclosureBudget globalDisclosureBudget
	) {
		this.maxInFlightTotal = requireRange(maxInFlightTotal, 1, MAX_CONFIGURED_JOBS, "maxInFlightTotal");
		this.maxInFlightPerOwner = requireRange(maxInFlightPerOwner, 1, MAX_CONFIGURED_JOBS, "maxInFlightPerOwner");
		if (maxInFlightPerOwner > maxInFlightTotal) {
			throw new IllegalArgumentException("maxInFlightPerOwner must not exceed maxInFlightTotal");
		}
		this.maxPendingTranscriptEntries = requireRange(
			maxPendingTranscriptEntries,
			1,
			MAX_PENDING_TRANSCRIPT_ENTRIES,
			"maxPendingTranscriptEntries"
		);
		this.maxDisclosedEntriesPerOwner = requireRange(
			maxDisclosedEntriesPerOwner,
			1,
			MAX_DISCLOSED_ENTRIES_PER_OWNER,
			"maxDisclosedEntriesPerOwner"
		);
		this.jobTimeoutNanos = requirePositiveNanos(jobTimeout, "jobTimeout");
		this.terminalRetentionNanos = requirePositiveNanos(terminalRetention, "terminalRetention");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
		this.jobIdSupplier = Objects.requireNonNull(jobIdSupplier, "jobIdSupplier");
		this.contextIdSupplier = Objects.requireNonNull(contextIdSupplier, "contextIdSupplier");
		this.authenticatorSupplier = Objects.requireNonNull(authenticatorSupplier, "authenticatorSupplier");
		this.globalDisclosureBudget = Objects.requireNonNull(globalDisclosureBudget, "globalDisclosureBudget");
	}

	/** Starts a fresh server lifetime. Per-process owner state resets; the injected global budget does not. */
	public synchronized OpaqueWorldgenContextId start() {
		if (session != null) {
			throw new IllegalStateException("Seeded-leaf authority is already active");
		}
		entries.clear();
		pendingByOwner.clear();
		disclosedEntriesByOwner.clear();
		pendingCount = 0;
		pendingTranscriptEntries = 0;
		return rotateSession();
	}

	/** Rotates context/key for a datapack reload while conservatively retaining disclosure counters. */
	public synchronized List<AuthorizedSeededLeafJob> reload() {
		requireActive();
		List<AuthorizedSeededLeafJob> cancelled = cancelAllInternal(nanoTime.getAsLong());
		rotateSession();
		return cancelled;
	}

	/** Cancels all work and drops the live key/context. */
	public synchronized List<AuthorizedSeededLeafJob> stop() {
		if (session == null) {
			return List.of();
		}
		List<AuthorizedSeededLeafJob> cancelled = cancelAllInternal(nanoTime.getAsLong());
		session = null;
		return cancelled;
	}

	public synchronized IssueResult tryIssue(UUID ownerId, SeededLeafJobSpec spec) {
		requireNonZeroUuid(ownerId, "ownerId");
		Objects.requireNonNull(spec, "spec");
		if (session == null) {
			return IssueResult.rejected(IssueStatus.SERVER_INACTIVE);
		}
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		if (pendingCount >= maxInFlightTotal) {
			return IssueResult.rejected(IssueStatus.GLOBAL_LIMIT_REACHED);
		}
		if (pendingByOwner.getOrDefault(ownerId, 0) >= maxInFlightPerOwner) {
			return IssueResult.rejected(IssueStatus.OWNER_LIMIT_REACHED);
		}
		if (!disclosedEntriesByOwner.containsKey(ownerId)
			&& disclosedEntriesByOwner.size() >= MAX_TRACKED_DISCLOSURE_OWNERS) {
			return IssueResult.rejected(IssueStatus.GLOBAL_DISCLOSURE_OWNER_LIMIT_REACHED);
		}
		if (spec.transcript().size() > maxPendingTranscriptEntries - pendingTranscriptEntries) {
			return IssueResult.rejected(IssueStatus.GLOBAL_TRANSCRIPT_LIMIT_REACHED);
		}
		int disclosed = disclosedEntriesByOwner.getOrDefault(ownerId, 0);
		if (spec.transcript().size() > maxDisclosedEntriesPerOwner - disclosed) {
			return IssueResult.rejected(IssueStatus.OWNER_DISCLOSURE_LIMIT_REACHED);
		}
		if (!makeTrackedSlotAvailable()) {
			return IssueResult.rejected(IssueStatus.GLOBAL_LIMIT_REACHED);
		}
		SeededLeafGlobalDisclosureBudget.ChargeStatus globalCharge = globalDisclosureBudget.tryCharge(spec.transcript().size());
		if (globalCharge == SeededLeafGlobalDisclosureBudget.ChargeStatus.EXHAUSTED) {
			return IssueResult.rejected(IssueStatus.GLOBAL_DISCLOSURE_LIMIT_REACHED);
		}
		if (globalCharge == SeededLeafGlobalDisclosureBudget.ChargeStatus.UNAVAILABLE) {
			return IssueResult.rejected(IssueStatus.GLOBAL_DISCLOSURE_BUDGET_UNAVAILABLE);
		}

		SeededLeafJob job = new SeededLeafJob(nextJobId(), session.contextId, spec);
		AuthorizedSeededLeafJob authorization = session.authenticator.authorize(job);
		entries.put(job.jobId(), new Entry(ownerId, authorization, now + jobTimeoutNanos));
		pendingCount++;
		pendingTranscriptEntries += spec.transcript().size();
		pendingByOwner.merge(ownerId, 1, Integer::sum);
		disclosedEntriesByOwner.put(ownerId, disclosed + spec.transcript().size());
		return IssueResult.accepted(authorization);
	}

	public synchronized ClaimStatus claimResponse(UUID ownerId, SeededLeafJobClaim claim) {
		return claim(ownerId, claim).status();
	}

	/** Claims a response once and returns the server-retained job only to the successful caller. */
	public synchronized ClaimResult claim(UUID ownerId, SeededLeafJobClaim claim) {
		requireNonZeroUuid(ownerId, "ownerId");
		Objects.requireNonNull(claim, "claim");
		if (session == null) {
			return ClaimResult.rejected(ClaimStatus.SERVER_INACTIVE, contextGeneration);
		}
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		Entry entry = entries.get(claim.jobId());
		if (entry == null) {
			return ClaimResult.rejected(ClaimStatus.UNKNOWN_JOB, contextGeneration);
		}
		if (!entry.ownerId.equals(ownerId)) {
			return ClaimResult.rejected(ClaimStatus.OWNER_MISMATCH, contextGeneration);
		}
		if (!entry.claim.contextId().equals(claim.contextId())) {
			return ClaimResult.rejected(ClaimStatus.CONTEXT_MISMATCH, contextGeneration);
		}
		if (!MessageDigest.isEqual(
			entry.claim.authenticationTag().bytes(),
			claim.authenticationTag().bytes()
		)) {
			return ClaimResult.rejected(ClaimStatus.AUTHENTICATION_FAILED, contextGeneration);
		}
		if (entry.state == State.PENDING && isDeadlineReached(now, entry.deadlineNanos)) {
			transitionFromPending(entry, State.EXPIRED, now);
		}
		return switch (entry.state) {
			case PENDING -> {
				AuthorizedSeededLeafJob authorization = entry.authorization;
				transitionFromPending(entry, State.RESPONSE_ACCEPTED, now);
				yield ClaimResult.accepted(authorization, contextGeneration);
			}
			case RESPONSE_ACCEPTED -> ClaimResult.rejected(ClaimStatus.DUPLICATE, contextGeneration);
			case CANCELLED -> ClaimResult.rejected(ClaimStatus.CANCELLED, contextGeneration);
			case EXPIRED -> ClaimResult.rejected(ClaimStatus.EXPIRED, contextGeneration);
		};
	}

	public synchronized CancellationStatus cancel(UUID ownerId, UUID jobId) {
		requireNonZeroUuid(ownerId, "ownerId");
		Objects.requireNonNull(jobId, "jobId");
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		Entry entry = entries.get(jobId);
		if (entry == null) {
			return CancellationStatus.UNKNOWN_JOB;
		}
		if (!entry.ownerId.equals(ownerId)) {
			return CancellationStatus.OWNER_MISMATCH;
		}
		if (entry.state == State.PENDING && isDeadlineReached(now, entry.deadlineNanos)) {
			transitionFromPending(entry, State.EXPIRED, now);
		}
		return switch (entry.state) {
			case PENDING -> {
				transitionFromPending(entry, State.CANCELLED, now);
				yield CancellationStatus.CANCELLED;
			}
			case RESPONSE_ACCEPTED -> CancellationStatus.ALREADY_RESPONDED;
			case CANCELLED -> CancellationStatus.ALREADY_CANCELLED;
			case EXPIRED -> CancellationStatus.EXPIRED;
		};
	}

	public synchronized List<AuthorizedSeededLeafJob> cancelAllForOwner(UUID ownerId) {
		requireNonZeroUuid(ownerId, "ownerId");
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		List<AuthorizedSeededLeafJob> cancelled = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.state == State.PENDING && entry.ownerId.equals(ownerId)) {
				AuthorizedSeededLeafJob authorization = entry.authorization;
				transitionFromPending(entry, State.CANCELLED, now);
				cancelled.add(authorization);
			}
		}
		return List.copyOf(cancelled);
	}

	public synchronized List<AuthorizedSeededLeafJob> expireTimedOut() {
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		List<AuthorizedSeededLeafJob> expired = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.state == State.PENDING && isDeadlineReached(now, entry.deadlineNanos)) {
				AuthorizedSeededLeafJob authorization = entry.authorization;
				transitionFromPending(entry, State.EXPIRED, now);
				expired.add(authorization);
			}
		}
		return List.copyOf(expired);
	}

	public synchronized boolean isActive() {
		return session != null;
	}

	public synchronized Optional<OpaqueWorldgenContextId> contextId() {
		return session == null ? Optional.empty() : Optional.of(session.contextId);
	}

	public synchronized long contextGeneration() {
		return contextGeneration;
	}

	public synchronized int pendingCount() {
		return pendingCount;
	}

	public synchronized int pendingTranscriptEntries() {
		return pendingTranscriptEntries;
	}

	public synchronized int pendingCountFor(UUID ownerId) {
		requireNonZeroUuid(ownerId, "ownerId");
		return pendingByOwner.getOrDefault(ownerId, 0);
	}

	public synchronized int disclosedEntriesFor(UUID ownerId) {
		requireNonZeroUuid(ownerId, "ownerId");
		return disclosedEntriesByOwner.getOrDefault(ownerId, 0);
	}

	public SeededLeafGlobalDisclosureBudget.Snapshot globalDisclosureSnapshot() {
		return globalDisclosureBudget.snapshot();
	}

	private OpaqueWorldgenContextId rotateSession() {
		long nextGeneration = Math.incrementExact(contextGeneration);
		OpaqueWorldgenContextId contextId = Objects.requireNonNull(contextIdSupplier.get(), "contextIdSupplier returned null");
		SeededLeafJobAuthenticator authenticator = Objects.requireNonNull(
			authenticatorSupplier.get(),
			"authenticatorSupplier returned null"
		);
		session = new Session(contextId, authenticator);
		contextGeneration = nextGeneration;
		return contextId;
	}

	private void requireActive() {
		if (session == null) {
			throw new IllegalStateException("Seeded-leaf authority is not active");
		}
	}

	private List<AuthorizedSeededLeafJob> cancelAllInternal(long now) {
		pruneTerminalEntries(now);
		List<AuthorizedSeededLeafJob> cancelled = new ArrayList<>(pendingCount);
		for (Entry entry : entries.values()) {
			if (entry.state == State.PENDING) {
				AuthorizedSeededLeafJob authorization = entry.authorization;
				transitionFromPending(entry, State.CANCELLED, now);
				cancelled.add(authorization);
			}
		}
		return List.copyOf(cancelled);
	}

	private UUID nextJobId() {
		for (int attempt = 0; attempt < MAX_JOB_ID_ATTEMPTS; attempt++) {
			UUID candidate = Objects.requireNonNull(jobIdSupplier.get(), "jobIdSupplier returned null");
			if (!isZeroUuid(candidate) && !entries.containsKey(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Unable to allocate a unique non-zero job ID after " + MAX_JOB_ID_ATTEMPTS + " attempts");
	}

	private boolean makeTrackedSlotAvailable() {
		if (entries.size() < maxInFlightTotal) {
			return true;
		}
		Iterator<Entry> iterator = entries.values().iterator();
		while (iterator.hasNext()) {
			if (iterator.next().state != State.PENDING) {
				iterator.remove();
				return true;
			}
		}
		return false;
	}

	private void transitionFromPending(Entry entry, State state, long now) {
		if (entry.state != State.PENDING || state == State.PENDING) {
			throw new IllegalStateException("Invalid seeded-leaf job transition: " + entry.state + " -> " + state);
		}
		entry.state = state;
		entry.terminalNanos = now;
		pendingCount--;
		pendingTranscriptEntries -= entry.authorization.job().transcript().size();
		entry.authorization = null;
		pendingByOwner.compute(entry.ownerId, (owner, count) -> count == null || count <= 1 ? null : count - 1);
	}

	private void pruneTerminalEntries(long now) {
		entries.values().removeIf(entry -> entry.state != State.PENDING
			&& isDeadlineReached(now, entry.terminalNanos + terminalRetentionNanos));
	}

	private static boolean isDeadlineReached(long now, long deadline) {
		return now - deadline >= 0L;
	}

	private static int requireRange(int value, int minimum, int maximum, String name) {
		if (value < minimum || value > maximum) {
			throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum + ": " + value);
		}
		return value;
	}

	private static long requirePositiveNanos(Duration duration, String name) {
		Objects.requireNonNull(duration, name);
		long nanos;
		try {
			nanos = duration.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(name + " is too large", exception);
		}
		if (nanos <= 0L) {
			throw new IllegalArgumentException(name + " must be at least one nanosecond");
		}
		return nanos;
	}

	private static void requireNonZeroUuid(UUID value, String name) {
		Objects.requireNonNull(value, name);
		if (isZeroUuid(value)) {
			throw new IllegalArgumentException(name + " must not be the all-zero UUID");
		}
	}

	private static boolean isZeroUuid(UUID value) {
		return value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L;
	}

	public enum IssueStatus {
		ACCEPTED,
		SERVER_INACTIVE,
		GLOBAL_LIMIT_REACHED,
		OWNER_LIMIT_REACHED,
		GLOBAL_TRANSCRIPT_LIMIT_REACHED,
		GLOBAL_DISCLOSURE_OWNER_LIMIT_REACHED,
		GLOBAL_DISCLOSURE_LIMIT_REACHED,
		GLOBAL_DISCLOSURE_BUDGET_UNAVAILABLE,
		OWNER_DISCLOSURE_LIMIT_REACHED
	}

	public enum ClaimStatus {
		ACCEPTED,
		SERVER_INACTIVE,
		UNKNOWN_JOB,
		OWNER_MISMATCH,
		CONTEXT_MISMATCH,
		AUTHENTICATION_FAILED,
		EXPIRED,
		DUPLICATE,
		CANCELLED
	}

	public enum CancellationStatus {
		CANCELLED,
		UNKNOWN_JOB,
		OWNER_MISMATCH,
		EXPIRED,
		ALREADY_CANCELLED,
		ALREADY_RESPONDED
	}

	public record IssueResult(IssueStatus status, Optional<AuthorizedSeededLeafJob> authorization) {
		public IssueResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(authorization, "authorization");
			if ((status == IssueStatus.ACCEPTED) != authorization.isPresent()) {
				throw new IllegalArgumentException("Only accepted issues carry an authorized job");
			}
		}

		private static IssueResult accepted(AuthorizedSeededLeafJob authorization) {
			return new IssueResult(IssueStatus.ACCEPTED, Optional.of(authorization));
		}

		private static IssueResult rejected(IssueStatus status) {
			return new IssueResult(status, Optional.empty());
		}
	}

	public record ClaimResult(
		ClaimStatus status,
		Optional<AuthorizedSeededLeafJob> authorization,
		long contextGeneration
	) {
		public ClaimResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(authorization, "authorization");
			if (contextGeneration < 0L || (status == ClaimStatus.ACCEPTED && contextGeneration == 0L)) {
				throw new IllegalArgumentException("Invalid claim context generation: " + contextGeneration);
			}
			if ((status == ClaimStatus.ACCEPTED) != authorization.isPresent()) {
				throw new IllegalArgumentException("Only accepted claims carry the server-retained authorized job");
			}
		}

		private static ClaimResult accepted(AuthorizedSeededLeafJob authorization, long contextGeneration) {
			return new ClaimResult(ClaimStatus.ACCEPTED, Optional.of(authorization), contextGeneration);
		}

		private static ClaimResult rejected(ClaimStatus status, long contextGeneration) {
			return new ClaimResult(status, Optional.empty(), contextGeneration);
		}
	}

	private enum State {
		PENDING,
		RESPONSE_ACCEPTED,
		CANCELLED,
		EXPIRED
	}

	private record Session(OpaqueWorldgenContextId contextId, SeededLeafJobAuthenticator authenticator) {
	}

	private static final class Entry {
		private final UUID ownerId;
		private final SeededLeafJobClaim claim;
		private final long deadlineNanos;
		private AuthorizedSeededLeafJob authorization;
		private State state = State.PENDING;
		private long terminalNanos;

		private Entry(UUID ownerId, AuthorizedSeededLeafJob authorization, long deadlineNanos) {
			this.ownerId = ownerId;
			this.authorization = authorization;
			this.claim = SeededLeafJobClaim.fromAuthorization(authorization);
			this.deadlineNanos = deadlineNanos;
		}
	}
}
