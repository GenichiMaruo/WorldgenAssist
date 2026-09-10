package io.github.genichimaruo.worldgenassist.server;

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

import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;

public final class PendingTerrainJobRegistry {
	public static final int MAX_CONFIGURED_JOBS = 65_536;
	private static final int MAX_JOB_ID_ATTEMPTS = 32;

	private final int maxInFlightTotal;
	private final int maxInFlightPerOwner;
	private final long jobTimeoutNanos;
	private final long terminalRetentionNanos;
	private final LongSupplier nanoTime;
	private final Supplier<UUID> jobIdSupplier;
	private final Map<UUID, Entry> entries = new LinkedHashMap<>();
	private final Map<UUID, Integer> pendingByOwner = new HashMap<>();

	private int pendingCount;

	public PendingTerrainJobRegistry(
		int maxInFlightTotal,
		int maxInFlightPerOwner,
		Duration jobTimeout,
		Duration terminalRetention
	) {
		this(
			maxInFlightTotal,
			maxInFlightPerOwner,
			jobTimeout,
			terminalRetention,
			System::nanoTime,
			UUID::randomUUID
		);
	}

	PendingTerrainJobRegistry(
		int maxInFlightTotal,
		int maxInFlightPerOwner,
		Duration jobTimeout,
		Duration terminalRetention,
		LongSupplier nanoTime,
		Supplier<UUID> jobIdSupplier
	) {
		this.maxInFlightTotal = requireJobLimit(maxInFlightTotal, "maxInFlightTotal");
		this.maxInFlightPerOwner = requireJobLimit(maxInFlightPerOwner, "maxInFlightPerOwner");
		if (maxInFlightPerOwner > maxInFlightTotal) {
			throw new IllegalArgumentException("maxInFlightPerOwner must not exceed maxInFlightTotal");
		}
		this.jobTimeoutNanos = requirePositiveNanos(jobTimeout, "jobTimeout");
		this.terminalRetentionNanos = requirePositiveNanos(terminalRetention, "terminalRetention");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
		this.jobIdSupplier = Objects.requireNonNull(jobIdSupplier, "jobIdSupplier");
	}

	public synchronized RegistrationResult tryRegister(
		UUID ownerId,
		Identifier dimension,
		int chunkX,
		int chunkZ,
		WorldgenContextFingerprint contextFingerprint
	) {
		requireNonZeroUuid(ownerId, "ownerId");
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		if (pendingCount >= maxInFlightTotal) {
			return RegistrationResult.rejected(RegistrationStatus.GLOBAL_LIMIT_REACHED);
		}
		if (pendingByOwner.getOrDefault(ownerId, 0) >= maxInFlightPerOwner) {
			return RegistrationResult.rejected(RegistrationStatus.OWNER_LIMIT_REACHED);
		}
		if (!makeTrackedSlotAvailable()) {
			return RegistrationResult.rejected(RegistrationStatus.GLOBAL_LIMIT_REACHED);
		}

		TerrainJobIdentity identity = new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			nextJobId(),
			dimension,
			chunkX,
			chunkZ,
			contextFingerprint
		);
		entries.put(identity.jobId(), new Entry(ownerId, identity, now + jobTimeoutNanos));
		pendingCount++;
		pendingByOwner.merge(ownerId, 1, Integer::sum);
		return RegistrationResult.accepted(identity);
	}

	public synchronized ResponseStatus evaluateResponse(UUID ownerId, TerrainJobIdentity responseIdentity) {
		return responseStatus(ownerId, responseIdentity, true);
	}

	public synchronized ResponseStatus inspectResponse(UUID ownerId, TerrainJobIdentity responseIdentity) {
		return responseStatus(ownerId, responseIdentity, false);
	}

	private ResponseStatus responseStatus(UUID ownerId, TerrainJobIdentity responseIdentity, boolean consume) {
		requireNonZeroUuid(ownerId, "ownerId");
		Objects.requireNonNull(responseIdentity, "responseIdentity");
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		Entry entry = entries.get(responseIdentity.jobId());
		if (entry == null) {
			return ResponseStatus.UNKNOWN_JOB;
		}
		if (!entry.ownerId.equals(ownerId)) {
			return ResponseStatus.OWNER_MISMATCH;
		}
		if (!entry.identity.equals(responseIdentity)) {
			return ResponseStatus.IDENTITY_MISMATCH;
		}
		if (entry.state == State.PENDING && isDeadlineReached(now, entry.deadlineNanos)) {
			transitionFromPending(entry, State.EXPIRED, now);
		}
		return switch (entry.state) {
			case PENDING -> {
				if (consume) {
					transitionFromPending(entry, State.RESPONSE_ACCEPTED, now);
				}
				yield ResponseStatus.ACCEPTED;
			}
			case RESPONSE_ACCEPTED -> ResponseStatus.DUPLICATE;
			case CANCELLED -> ResponseStatus.CANCELLED;
			case EXPIRED -> ResponseStatus.EXPIRED;
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

	public synchronized List<TerrainJobIdentity> cancelAllForOwner(UUID ownerId) {
		requireNonZeroUuid(ownerId, "ownerId");
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		List<TerrainJobIdentity> cancelled = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.state == State.PENDING && entry.ownerId.equals(ownerId)) {
				transitionFromPending(entry, State.CANCELLED, now);
				cancelled.add(entry.identity);
			}
		}
		return List.copyOf(cancelled);
	}

	public synchronized List<TerrainJobIdentity> cancelAll() {
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		List<TerrainJobIdentity> cancelled = new ArrayList<>(pendingCount);
		for (Entry entry : entries.values()) {
			if (entry.state == State.PENDING) {
				transitionFromPending(entry, State.CANCELLED, now);
				cancelled.add(entry.identity);
			}
		}
		return List.copyOf(cancelled);
	}

	public synchronized List<TerrainJobIdentity> expireTimedOut() {
		long now = nanoTime.getAsLong();
		pruneTerminalEntries(now);
		List<TerrainJobIdentity> expired = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.state == State.PENDING && isDeadlineReached(now, entry.deadlineNanos)) {
				transitionFromPending(entry, State.EXPIRED, now);
				expired.add(entry.identity);
			}
		}
		return List.copyOf(expired);
	}

	public synchronized int pendingCount() {
		return pendingCount;
	}

	public synchronized int pendingCountFor(UUID ownerId) {
		requireNonZeroUuid(ownerId, "ownerId");
		return pendingByOwner.getOrDefault(ownerId, 0);
	}

	public synchronized int trackedCount() {
		pruneTerminalEntries(nanoTime.getAsLong());
		return entries.size();
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
			throw new IllegalStateException("Invalid pending-job transition: " + entry.state + " -> " + state);
		}
		entry.state = state;
		entry.terminalNanos = now;
		pendingCount--;
		pendingByOwner.compute(entry.ownerId, (ownerId, count) -> count == null || count <= 1 ? null : count - 1);
	}

	private void pruneTerminalEntries(long now) {
		entries.values().removeIf(entry -> entry.state != State.PENDING && isDeadlineReached(now, entry.terminalNanos + terminalRetentionNanos));
	}

	private static boolean isDeadlineReached(long now, long deadline) {
		return now - deadline >= 0L;
	}

	private static int requireJobLimit(int value, String name) {
		if (value < 1 || value > MAX_CONFIGURED_JOBS) {
			throw new IllegalArgumentException(name + " must be between 1 and " + MAX_CONFIGURED_JOBS + ": " + value);
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

	public enum RegistrationStatus {
		ACCEPTED,
		GLOBAL_LIMIT_REACHED,
		OWNER_LIMIT_REACHED
	}

	public enum ResponseStatus {
		ACCEPTED,
		UNKNOWN_JOB,
		OWNER_MISMATCH,
		IDENTITY_MISMATCH,
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

	public record RegistrationResult(RegistrationStatus status, Optional<TerrainJobIdentity> identity) {
		public RegistrationResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(identity, "identity");
			if ((status == RegistrationStatus.ACCEPTED) != identity.isPresent()) {
				throw new IllegalArgumentException("Only accepted registrations carry a job identity");
			}
		}

		private static RegistrationResult accepted(TerrainJobIdentity identity) {
			return new RegistrationResult(RegistrationStatus.ACCEPTED, Optional.of(identity));
		}

		private static RegistrationResult rejected(RegistrationStatus status) {
			return new RegistrationResult(status, Optional.empty());
		}
	}

	private enum State {
		PENDING,
		RESPONSE_ACCEPTED,
		CANCELLED,
		EXPIRED
	}

	private static final class Entry {
		private final UUID ownerId;
		private final TerrainJobIdentity identity;
		private final long deadlineNanos;
		private State state = State.PENDING;
		private long terminalNanos;

		private Entry(UUID ownerId, TerrainJobIdentity identity, long deadlineNanos) {
			this.ownerId = ownerId;
			this.identity = identity;
			this.deadlineNanos = deadlineNanos;
		}
	}
}
