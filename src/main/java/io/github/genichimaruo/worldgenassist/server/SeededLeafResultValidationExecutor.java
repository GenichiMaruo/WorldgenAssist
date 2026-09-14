package io.github.genichimaruo.worldgenassist.server;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;

/**
 * Bounded single-worker decode and authoritative-validation stage for the
 * unregistered seeded-leaf protocol. Completion never applies world state.
 */
public final class SeededLeafResultValidationExecutor implements AutoCloseable {
	public static final int MAX_TASKS = 64;
	private static final String WORKER_THREAD_NAME = "CAWG-SeededLeafValidate";
	private static final String TIMEOUT_THREAD_NAME = "CAWG-SeededLeafValidationTimeout";
	private static final AdmissionHook NO_ADMISSION_HOOK = claimed -> {
	};
	private static final WorkerHook NO_WORKER_HOOK = claimed -> {
	};

	private final SeededLeafJobAuthority authority;
	private final int validationSampleCells;
	private final long processingTimeoutNanos;
	private final SecureRandom selectionRandom;
	private final AdmissionHook admissionHook;
	private final WorkerHook workerHook;
	private final Semaphore capacity;
	private final ThreadPoolExecutor worker;
	private final ScheduledThreadPoolExecutor timeoutExecutor;
	private final ConcurrentHashMap<UUID, TaskHandle> tasks = new ConcurrentHashMap<>();
	private final Object ownerEpochLock = new Object();
	private final Map<UUID, OwnerEpoch> ownerEpochs = new HashMap<>();
	private final AtomicLong lifecycleEpoch = new AtomicLong();
	private final AtomicBoolean closed = new AtomicBoolean();
	private boolean ownerEpochTrackingUnavailable;

	public SeededLeafResultValidationExecutor(
		SeededLeafJobAuthority authority,
		int maximumTasks,
		int validationSampleCells,
		Duration processingTimeout
	) {
		this(authority, maximumTasks, validationSampleCells, processingTimeout, new SecureRandom());
	}

	SeededLeafResultValidationExecutor(
		SeededLeafJobAuthority authority,
		int maximumTasks,
		int validationSampleCells,
		Duration processingTimeout,
		SecureRandom selectionRandom
	) {
		this(
			authority,
			maximumTasks,
			validationSampleCells,
			processingTimeout,
			selectionRandom,
			NO_ADMISSION_HOOK,
			NO_WORKER_HOOK
		);
	}

	SeededLeafResultValidationExecutor(
		SeededLeafJobAuthority authority,
		int maximumTasks,
		int validationSampleCells,
		Duration processingTimeout,
		SecureRandom selectionRandom,
		AdmissionHook admissionHook,
		WorkerHook workerHook
	) {
		this.authority = Objects.requireNonNull(authority, "authority");
		if (maximumTasks < 1 || maximumTasks > MAX_TASKS) {
			throw new IllegalArgumentException("maximumTasks must be between 1 and " + MAX_TASKS + ": " + maximumTasks);
		}
		if (validationSampleCells < 1 || validationSampleCells > RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS) {
			throw new IllegalArgumentException(
				"validationSampleCells must be between 1 and " + RemoteWorldgenConfig.MAX_VALIDATION_SAMPLE_CELLS
					+ ": " + validationSampleCells
			);
		}
		this.validationSampleCells = validationSampleCells;
		Objects.requireNonNull(processingTimeout, "processingTimeout");
		try {
			processingTimeoutNanos = processingTimeout.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("processingTimeout is too large", exception);
		}
		if (processingTimeoutNanos < TimeUnit.MILLISECONDS.toNanos(RemoteWorldgenConfig.MIN_TIMEOUT_MILLIS)
			|| processingTimeoutNanos > TimeUnit.MILLISECONDS.toNanos(RemoteWorldgenConfig.MAX_TIMEOUT_MILLIS)) {
			throw new IllegalArgumentException(
				"processingTimeout must be between " + RemoteWorldgenConfig.MIN_TIMEOUT_MILLIS + " and "
					+ RemoteWorldgenConfig.MAX_TIMEOUT_MILLIS + " milliseconds"
			);
		}
		this.selectionRandom = Objects.requireNonNull(selectionRandom, "selectionRandom");
		this.admissionHook = Objects.requireNonNull(admissionHook, "admissionHook");
		this.workerHook = Objects.requireNonNull(workerHook, "workerHook");
		capacity = new Semaphore(maximumTasks);
		worker = new ThreadPoolExecutor(
			1,
			1,
			0L,
			TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(maximumTasks),
			task -> daemonThread(task, WORKER_THREAD_NAME),
			new ThreadPoolExecutor.AbortPolicy()
		);
		timeoutExecutor = new ScheduledThreadPoolExecutor(1, task -> daemonThread(task, TIMEOUT_THREAD_NAME));
		timeoutExecutor.setRemoveOnCancelPolicy(true);
		timeoutExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
	}

	/**
	 * Claims synchronously, then schedules only bounded decode/validation work.
	 * The returned future completes on the validation worker or immediately for
	 * admission failures; callers must marshal successful application themselves.
	 */
	public CompletableFuture<Outcome> submit(
		UUID ownerId,
		SeededLeafDensityResultEnvelope envelope,
		ValidationContext validationContext
	) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(envelope, "envelope");
		Objects.requireNonNull(validationContext, "validationContext");
		if (closed.get()) {
			return CompletableFuture.completedFuture(Outcome.rejected(Status.CLOSED, Optional.empty()));
		}
		long admittedEpoch = lifecycleEpoch.get();
		OwnerAdmission ownerAdmission = captureOwnerAdmission(ownerId);
		if (!ownerAdmission.active()) {
			return CompletableFuture.completedFuture(Outcome.rejected(ownerAdmission.rejectionStatus(), Optional.empty()));
		}
		if (!capacity.tryAcquire()) {
			return CompletableFuture.completedFuture(Outcome.rejected(Status.QUEUE_FULL, Optional.empty()));
		}
		if (closed.get() || admittedEpoch != lifecycleEpoch.get()) {
			capacity.release();
			return CompletableFuture.completedFuture(Outcome.rejected(
				closed.get() ? Status.CLOSED : Status.STALE_CONTEXT,
				Optional.empty()
			));
		}
		if (!ownerAdmissionStillValid(ownerId, ownerAdmission.epoch())) {
			capacity.release();
			return CompletableFuture.completedFuture(Outcome.rejected(currentOwnerRejectionStatus(ownerId), Optional.empty()));
		}

		SeededLeafDensityResultGate.ClaimGateResult claim = SeededLeafDensityResultGate.claimBeforeDecode(
			authority,
			ownerId,
			envelope
		);
		if (claim.status() == SeededLeafDensityResultGate.ClaimGateStatus.CLAIM_REJECTED) {
			capacity.release();
			return CompletableFuture.completedFuture(Outcome.rejected(
				Status.CLAIM_REJECTED,
				Optional.of(claim.claimStatus())
			));
		}
		if (claim.status() == SeededLeafDensityResultGate.ClaimGateStatus.SHAPE_MISMATCH) {
			capacity.release();
			return CompletableFuture.completedFuture(Outcome.rejected(
				Status.SHAPE_MISMATCH,
				Optional.of(claim.claimStatus())
			));
		}

		SeededLeafDensityResultGate.ClaimedResult claimed = claim.claimedResult().orElseThrow();
		try {
			admissionHook.afterClaimBeforeRegistration(claimed);
		} catch (RuntimeException exception) {
			capacity.release();
			return CompletableFuture.completedFuture(Outcome.rejected(
				Status.INTERNAL_REJECTION,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
			));
		}
		CompletableFuture<Outcome> completion = new CompletableFuture<>();
		TaskHandle handle = new TaskHandle(
			ownerId,
			claimed.geometry().jobId(),
			admittedEpoch,
			ownerAdmission.epoch(),
			claimed.authorityGeneration(),
			completion
		);
		if (tasks.putIfAbsent(handle.jobId, handle) != null) {
			capacity.release();
			return CompletableFuture.completedFuture(Outcome.rejected(
				Status.INTERNAL_REJECTION,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
			));
		}
		if (closed.get() || admittedEpoch != lifecycleEpoch.get()
			|| !ownerAdmissionStillValid(ownerId, ownerAdmission.epoch())) {
			Status status = closed.get()
				? Status.CLOSED
				: admittedEpoch != lifecycleEpoch.get() ? Status.STALE_CONTEXT : currentOwnerRejectionStatus(ownerId);
			finish(handle, Outcome.rejected(status, Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)), false);
			releaseResources(handle);
			return completion;
		}

		long selectionSeed = selectionRandom.nextLong();
		handle.work = () -> process(handle, claimed, validationContext, selectionSeed);
		try {
			handle.timeout = timeoutExecutor.schedule(
				() -> finish(handle, Outcome.rejected(
					Status.TIMED_OUT,
					Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
				), true),
				processingTimeoutNanos,
				TimeUnit.NANOSECONDS
			);
			worker.execute(handle);
		} catch (RejectedExecutionException exception) {
			finish(handle, Outcome.rejected(
				closed.get() ? Status.CLOSED : Status.INTERNAL_REJECTION,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
			), true);
			releaseResources(handle);
		}
		return completion;
	}

	/** Opens a new owner connection epoch after Fabric has accepted the connection. */
	public void connectOwner(UUID ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		synchronized (ownerEpochLock) {
			OwnerEpoch previous = ownerEpochs.get(ownerId);
			if (previous == null) {
				if (ownerEpochs.size() >= SeededLeafJobAuthority.MAX_TRACKED_DISCLOSURE_OWNERS) {
					ownerEpochTrackingUnavailable = true;
				} else {
					ownerEpochs.put(ownerId, new OwnerEpoch(1L, true));
				}
			} else {
				if (previous.epoch() == Long.MAX_VALUE) {
					ownerEpochTrackingUnavailable = true;
				} else {
					ownerEpochs.put(ownerId, new OwnerEpoch(previous.epoch() + 1L, true));
				}
			}
		}
	}

	/** Prevents new submissions for an owner before cancelling its current work. */
	public int disconnectOwner(UUID ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		synchronized (ownerEpochLock) {
			OwnerEpoch previous = ownerEpochs.get(ownerId);
			if (previous == null) {
				if (ownerEpochs.size() >= SeededLeafJobAuthority.MAX_TRACKED_DISCLOSURE_OWNERS) {
					ownerEpochTrackingUnavailable = true;
				} else {
					ownerEpochs.put(ownerId, new OwnerEpoch(1L, false));
				}
			} else {
				if (previous.epoch() == Long.MAX_VALUE) {
					ownerEpochTrackingUnavailable = true;
				} else {
					ownerEpochs.put(ownerId, new OwnerEpoch(previous.epoch() + 1L, false));
				}
			}
		}
		return cancelMatching(ownerId, Status.CANCELLED);
	}

	/** Atomically closes executor admission for the old authority generation and rotates it. */
	ReloadResult invalidateAndReloadAuthority() {
		synchronized (authority) {
			lifecycleEpoch.incrementAndGet();
			int cancelledValidations = cancelAll();
			int cancelledJobs = authority.reload().size();
			return new ReloadResult(cancelledValidations, cancelledJobs, authority.contextGeneration());
		}
	}

	public int cancelOwner(UUID ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		return cancelMatching(ownerId, Status.CANCELLED);
	}

	public int cancelAll() {
		List<TaskHandle> snapshot = new ArrayList<>(tasks.values());
		int cancelled = 0;
		for (TaskHandle handle : snapshot) {
			if (finish(handle, Outcome.rejected(
				Status.CANCELLED,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
			), true)) {
				cancelled++;
			}
		}
		return cancelled;
	}

	public int pendingCount() {
		return tasks.size();
	}

	boolean hasPendingOwner(UUID ownerId) {
		return tasks.values().stream().anyMatch(task -> task.ownerId.equals(ownerId));
	}

	java.util.Set<UUID> pendingOwners() {
		java.util.Set<UUID> owners = new java.util.HashSet<>();
		tasks.values().forEach(task -> owners.add(task.ownerId));
		return owners;
	}

	/** Read-only preparation admission; submit still rechecks the captured owner epoch. */
	boolean isOwnerActive(UUID ownerId) {
		return !closed.get() && captureOwnerAdmission(Objects.requireNonNull(ownerId, "ownerId")).active();
	}

	public boolean isClosed() {
		return closed.get();
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		lifecycleEpoch.incrementAndGet();
		cancelAll();
		for (Runnable abandoned : worker.shutdownNow()) {
			if (!(abandoned instanceof SeededLeafResultValidationExecutor.TaskHandle handle)) {
				continue;
			}
			finish(handle, Outcome.rejected(
				Status.CLOSED,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
			), false);
			releaseResources(handle);
		}
		timeoutExecutor.shutdownNow();
	}

	private void process(
		TaskHandle handle,
		SeededLeafDensityResultGate.ClaimedResult claimed,
		ValidationContext validationContext,
		long selectionSeed
	) {
		if (handle.finished.get()) {
			return;
		}
		try {
			if (handle.authorityGeneration != authority.contextGeneration()
				|| handle.lifecycleEpoch != lifecycleEpoch.get()
				|| !ownerAdmissionStillValid(handle.ownerId, handle.ownerEpoch)) {
				finish(handle, Outcome.rejected(
					handle.authorityGeneration != authority.contextGeneration()
						|| handle.lifecycleEpoch != lifecycleEpoch.get()
						? Status.STALE_CONTEXT : currentOwnerRejectionStatus(handle.ownerId),
					Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
				), false);
				return;
			}
			workerHook.beforeValidation(claimed);
			if (handle.finished.get()) {
				return;
			}
			SeededLeafDensityResultGate.GateResult decoded = SeededLeafDensityResultGate.decodeClaimed(claimed);
			if (decoded.status() != SeededLeafDensityResultGate.Status.ACCEPTED_FOR_AUTHORITATIVE_VALIDATION) {
				finish(handle, Outcome.rejected(
					Status.INVALID_DENSITY_DATA,
					Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
				), false);
				return;
			}
			SeededLeafDensityResultGate.AcceptedResult accepted = decoded.acceptedResult().orElseThrow();
			RemoteDensityValidator.ValidationMetrics metrics = RemoteDensityValidator.validate(
				accepted,
				validationSampleCells,
				validationContext.randomState,
				validationContext.settings,
				validationContext.noiseSettings,
				new Random(selectionSeed)
			);
			finishValidated(handle, accepted, metrics);
		} catch (RemoteDensityValidator.RemoteDensityValidationException exception) {
			finish(handle, Outcome.rejected(
				Status.VALIDATION_FAILED,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
			), false);
		} catch (RuntimeException exception) {
			finish(handle, Outcome.rejected(
				Thread.currentThread().isInterrupted() ? Status.CANCELLED : Status.INTERNAL_REJECTION,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
			), false);
		}
	}

	private int cancelMatching(UUID ownerId, Status status) {
		List<TaskHandle> snapshot = new ArrayList<>(tasks.values());
		int cancelled = 0;
		for (TaskHandle handle : snapshot) {
			if (handle.ownerId.equals(ownerId) && finish(handle, Outcome.rejected(
				status,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
			), true)) {
				cancelled++;
			}
		}
		return cancelled;
	}

	private boolean finish(TaskHandle handle, Outcome outcome, boolean interrupt) {
		if (!handle.finished.compareAndSet(false, true)) {
			return false;
		}
		ScheduledFuture<?> timeout = handle.timeout;
		if (timeout != null) {
			timeout.cancel(false);
		}
		if (interrupt) {
			Thread runner = handle.runner;
			if (runner != null) {
				runner.interrupt();
			} else if (worker.remove(handle)) {
				releaseResources(handle);
			}
		}
		handle.completion.complete(outcome);
		return true;
	}

	private void finishValidated(
		TaskHandle handle,
		SeededLeafDensityResultGate.AcceptedResult accepted,
		RemoteDensityValidator.ValidationMetrics metrics
	) {
		synchronized (authority) {
			if (handle.authorityGeneration != authority.contextGeneration()
				|| handle.lifecycleEpoch != lifecycleEpoch.get()) {
				finish(handle, Outcome.rejected(
					Status.STALE_CONTEXT,
					Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
				), false);
				return;
			}
			if (!ownerAdmissionStillValid(handle.ownerId, handle.ownerEpoch)) {
				finish(handle, Outcome.rejected(
					currentOwnerRejectionStatus(handle.ownerId),
					Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED)
				), false);
				return;
			}
			finish(handle, Outcome.validated(accepted, metrics, handle.authorityGeneration), false);
		}
	}

	private void releaseResources(TaskHandle handle) {
		if (!handle.resourcesReleased.compareAndSet(false, true)) {
			return;
		}
		ScheduledFuture<?> timeout = handle.timeout;
		if (timeout != null) {
			timeout.cancel(false);
		}
		handle.work = null;
		tasks.remove(handle.jobId, handle);
		capacity.release();
	}

	private OwnerAdmission captureOwnerAdmission(UUID ownerId) {
		synchronized (ownerEpochLock) {
			if (ownerEpochTrackingUnavailable) {
				return new OwnerAdmission(0L, false, Status.OWNER_STATE_UNAVAILABLE);
			}
			OwnerEpoch state = ownerEpochs.get(ownerId);
			if (state == null) {
				return new OwnerAdmission(0L, false, Status.OWNER_INACTIVE);
			}
			return new OwnerAdmission(state.epoch(), state.active(), Status.OWNER_INACTIVE);
		}
	}

	private boolean ownerAdmissionStillValid(UUID ownerId, long admittedOwnerEpoch) {
		synchronized (ownerEpochLock) {
			if (ownerEpochTrackingUnavailable) {
				return false;
			}
			OwnerEpoch state = ownerEpochs.get(ownerId);
			return state != null && state.active() && state.epoch() == admittedOwnerEpoch;
		}
	}

	private Status currentOwnerRejectionStatus(UUID ownerId) {
		synchronized (ownerEpochLock) {
			if (ownerEpochTrackingUnavailable) {
				return Status.OWNER_STATE_UNAVAILABLE;
			}
			OwnerEpoch state = ownerEpochs.get(ownerId);
			return state != null && !state.active() ? Status.OWNER_INACTIVE : Status.STALE_OWNER_CONNECTION;
		}
	}

	private static Thread daemonThread(Runnable task, String name) {
		Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		return thread;
	}

	/** Package-private deterministic test boundary after claim and before task registration. */
	@FunctionalInterface
	interface AdmissionHook {
		void afterClaimBeforeRegistration(SeededLeafDensityResultGate.ClaimedResult claimed);
	}

	/** Package-private deterministic test boundary on the real validation worker. */
	@FunctionalInterface
	interface WorkerHook {
		void beforeValidation(SeededLeafDensityResultGate.ClaimedResult claimed);
	}

	public record ValidationContext(
		RandomState randomState,
		NoiseGeneratorSettings settings,
		NoiseSettings noiseSettings
	) {
		public ValidationContext {
			Objects.requireNonNull(randomState, "randomState");
			Objects.requireNonNull(settings, "settings");
			Objects.requireNonNull(noiseSettings, "noiseSettings");
		}
	}

	record ReloadResult(int cancelledValidations, int cancelledJobs, long authorityGeneration) {
		ReloadResult {
			if (cancelledValidations < 0 || cancelledJobs < 0 || authorityGeneration < 1L) {
				throw new IllegalArgumentException("Invalid seeded-leaf reload result");
			}
		}
	}

	public enum Status {
		VALIDATED,
		CLAIM_REJECTED,
		SHAPE_MISMATCH,
		INVALID_DENSITY_DATA,
		VALIDATION_FAILED,
		QUEUE_FULL,
		OWNER_INACTIVE,
		OWNER_STATE_UNAVAILABLE,
		STALE_OWNER_CONNECTION,
		STALE_CONTEXT,
		TIMED_OUT,
		CANCELLED,
		CLOSED,
		INTERNAL_REJECTION
	}

	public static final class Outcome {
		private final Status status;
		private final Optional<SeededLeafJobAuthority.ClaimStatus> claimStatus;
		private final Optional<SeededLeafDensityResultGate.AcceptedResult> acceptedResult;
		private final int sampledCells;
		private final int sampledValues;
		private final long validationNanos;
		private final long authorityGeneration;

		private Outcome(
			Status status,
			Optional<SeededLeafJobAuthority.ClaimStatus> claimStatus,
			Optional<SeededLeafDensityResultGate.AcceptedResult> acceptedResult,
			int sampledCells,
			int sampledValues,
			long validationNanos,
			long authorityGeneration
		) {
			this.status = Objects.requireNonNull(status, "status");
			this.claimStatus = Objects.requireNonNull(claimStatus, "claimStatus");
			this.acceptedResult = Objects.requireNonNull(acceptedResult, "acceptedResult");
			this.sampledCells = sampledCells;
			this.sampledValues = sampledValues;
			this.validationNanos = validationNanos;
			this.authorityGeneration = authorityGeneration;
			if ((status == Status.VALIDATED) != acceptedResult.isPresent()) {
				throw new IllegalArgumentException("Only a validated outcome carries result data");
			}
			if (sampledCells < 0 || sampledValues < 0 || validationNanos < 0L) {
				throw new IllegalArgumentException("Validation metrics must not be negative");
			}
			if (status != Status.VALIDATED && (sampledCells != 0 || sampledValues != 0 || validationNanos != 0L)) {
				throw new IllegalArgumentException("Rejected outcomes must not carry validation metrics");
			}
			if ((status == Status.VALIDATED) != (authorityGeneration > 0L)) {
				throw new IllegalArgumentException("Only a validated outcome carries an authority generation");
			}
		}

		public Status status() {
			return status;
		}

		public Optional<SeededLeafJobAuthority.ClaimStatus> claimStatus() {
			return claimStatus;
		}

		public Optional<SeededLeafDensityResultGate.AcceptedResult> acceptedResult() {
			return acceptedResult;
		}

		public int sampledCells() {
			return sampledCells;
		}

		public int sampledValues() {
			return sampledValues;
		}

		public long validationNanos() {
			return validationNanos;
		}

		public long authorityGeneration() {
			return authorityGeneration;
		}

		private static Outcome validated(
			SeededLeafDensityResultGate.AcceptedResult result,
			RemoteDensityValidator.ValidationMetrics metrics,
			long authorityGeneration
		) {
			return new Outcome(
				Status.VALIDATED,
				Optional.of(SeededLeafJobAuthority.ClaimStatus.ACCEPTED),
				Optional.of(result),
				metrics.sampledCells(),
				metrics.sampledValues(),
				metrics.elapsedNanos(),
				authorityGeneration
			);
		}

		private static Outcome rejected(Status status, Optional<SeededLeafJobAuthority.ClaimStatus> claimStatus) {
			return new Outcome(status, claimStatus, Optional.empty(), 0, 0, 0L, 0L);
		}
	}

	private final class TaskHandle implements Runnable {
		private final UUID ownerId;
		private final UUID jobId;
		private final long lifecycleEpoch;
		private final long ownerEpoch;
		private final long authorityGeneration;
		private final CompletableFuture<Outcome> completion;
		private final AtomicBoolean finished = new AtomicBoolean();
		private final AtomicBoolean resourcesReleased = new AtomicBoolean();
		private volatile Runnable work;
		private volatile Thread runner;
		private volatile ScheduledFuture<?> timeout;

		private TaskHandle(
			UUID ownerId,
			UUID jobId,
			long lifecycleEpoch,
			long ownerEpoch,
			long authorityGeneration,
			CompletableFuture<Outcome> completion
		) {
			this.ownerId = ownerId;
			this.jobId = jobId;
			this.lifecycleEpoch = lifecycleEpoch;
			this.ownerEpoch = ownerEpoch;
			this.authorityGeneration = authorityGeneration;
			this.completion = completion;
		}

		@Override
		public void run() {
			runner = Thread.currentThread();
			try {
				Runnable currentWork = work;
				if (!finished.get() && currentWork != null) {
					currentWork.run();
				}
			} finally {
				runner = null;
				releaseResources(this);
			}
		}
	}

	private record OwnerEpoch(long epoch, boolean active) {
	}

	private record OwnerAdmission(long epoch, boolean active, Status rejectionStatus) {
	}
}
