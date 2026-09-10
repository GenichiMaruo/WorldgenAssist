package io.github.genichimaruo.worldgenassist.server;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;

/**
 * Transport-free, single-owner research orchestration. The opt-in public fixture
 * supplies its only live adapter. The supplied active authority is exclusively owned
 * by this object until close; its persistent disclosure budget remains external.
 * All control transitions share the authority monitor. Recording runs on a
 * dedicated worker; results use the separate bounded validation executor.
 */
public final class SeededLeafJobOrchestrator implements AutoCloseable {
	private final SeededLeafJobAuthority authority;
	private final Object offerSource = new Object();
	private final SeededLeafResultValidationExecutor validator;
	private final long timeoutNanos;
	private final RecordingHook recordingHook;
	private final LongSupplier nanoTime;
	private final ThreadPoolExecutor recorder;
	private final ScheduledThreadPoolExecutor watchdog;
	private Connection connection;
	private Attempt pending;
	private boolean recordingOccupied;
	private boolean changingLifecycle;
	private boolean closed;
	private int admissionSuspensions;

	public SeededLeafJobOrchestrator(SeededLeafJobAuthority authority, int sampleCells, Duration timeout) {
		this(authority, sampleCells, timeout, request -> { });
	}

	SeededLeafJobOrchestrator(
		SeededLeafJobAuthority authority, int sampleCells, Duration timeout, RecordingHook recordingHook
	) {
		this(authority, sampleCells, timeout, recordingHook, System::nanoTime);
	}

	SeededLeafJobOrchestrator(
		SeededLeafJobAuthority authority, int sampleCells, Duration timeout, RecordingHook recordingHook, LongSupplier nanoTime
	) {
		this.authority = Objects.requireNonNull(authority, "authority");
		this.recordingHook = Objects.requireNonNull(recordingHook, "recordingHook");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
		if (!authority.isActive()) {
			throw new IllegalArgumentException("Orchestration requires an active authority");
		}
		validator = new SeededLeafResultValidationExecutor(authority, 1, sampleCells, timeout);
		timeoutNanos = timeout.toNanos();
		recorder = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.NANOSECONDS,
			new ArrayBlockingQueue<>(1), work -> daemon(work, "CAWG-SeededLeafRecord"),
			new ThreadPoolExecutor.AbortPolicy());
		watchdog = new ScheduledThreadPoolExecutor(1, work -> daemon(work, "CAWG-SeededLeafAttemptTimeout"));
		watchdog.setRemoveOnCancelPolicy(true);
		watchdog.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
	}

	/** Called by the trusted server adapter, never from a client-selected UUID. */
	public Connection connect(UUID ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		if (ownerId.equals(new UUID(0L, 0L))) {
			throw new IllegalArgumentException("Owner ID must not be zero");
		}
		synchronized (authority) {
			if (closed || changingLifecycle) {
				throw new IllegalStateException("Orchestrator is closed or changing lifecycle");
			}
			changingLifecycle = true;
			try {
				if (connection != null) {
					deactivateConnection();
				}
				if (closed) {
					throw new IllegalStateException("Orchestrator closed during connection replacement");
				}
				connection = new Connection(ownerId);
				validator.connectOwner(ownerId);
				return connection;
			} finally {
				changingLifecycle = false;
			}
		}
	}

	/** Old disconnect callbacks cannot disconnect a newer connection of the same UUID. */
	public void disconnect(Connection owner) {
		synchronized (authority) {
			if (owner == null || owner != connection || changingLifecycle) {
				return;
			}
			changingLifecycle = true;
			try {
				deactivateConnection();
			} finally {
				changingLifecycle = false;
			}
		}
	}

	private void deactivateConnection() {
		Connection previous = connection;
		connection = null;
		if (pending != null) {
			finish(pending, Status.DISCONNECTED, null);
		}
		validator.disconnectOwner(previous.ownerId);
		authority.cancelAllForOwner(previous.ownerId);
	}

	/** No recording, ledger I/O, decompression, or world mutation on the submitting thread. */
	public Attempt submit(Connection owner, RecordingRequest request, ClientExchange exchange) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(exchange, "exchange");
		synchronized (authority) {
			Status rejected = closed ? Status.CLOSED
				: admissionSuspensions != 0 ? Status.BUSY
				: changingLifecycle ? Status.STALE_CONTEXT
				: owner == null || owner != connection ? Status.OWNER_INACTIVE
				: !validator.isOwnerActive(owner.ownerId) ? Status.OWNER_INACTIVE
				: owner.quarantined ? Status.WORKER_QUARANTINED
				: pending != null || recordingOccupied || validator.pendingCount() != 0 ? Status.BUSY
				: !authority.isActive() ? Status.STALE_CONTEXT : null;
			if (rejected == null) {
				SeededLeafGlobalDisclosureBudget.Snapshot disclosure = authority.globalDisclosureSnapshot();
				if (disclosure.state() != SeededLeafGlobalDisclosureBudget.State.ACTIVE || disclosure.remainingEntries() == 0L) {
					rejected = Status.AUTHORIZATION_REJECTED;
				}
			}
			Attempt attempt = new Attempt(offerSource, owner, authority.contextGeneration(), nanoTime.getAsLong() + timeoutNanos);
			if (rejected != null) {
				attempt.done = true;
				attempt.completion.complete(new Result(rejected, Optional.empty()));
				return attempt;
			}
			attempt.request = request;
			attempt.exchange = exchange;
			attempt.task = new RecordingTask(attempt);
			pending = attempt;
			recordingOccupied = true;
			try {
				attempt.timeout = watchdog.schedule(() -> {
					synchronized (authority) {
						finish(attempt, Status.TIMED_OUT, null);
					}
				}, timeoutNanos, TimeUnit.NANOSECONDS);
				recorder.execute(attempt.task);
			} catch (RejectedExecutionException exception) {
				recordingOccupied = false;
				attempt.task = null;
				finish(attempt, Status.CLOSED, null);
			}
			return attempt;
		}
	}

	public boolean cancel(Attempt attempt) {
		synchronized (authority) {
			return attempt != null && attempt == pending && finish(attempt, Status.CANCELLED, null);
		}
	}

	/** A vanilla synchronous load cannot pump fixture network ticks. Nested scopes are balanced by the adapter. */
	public boolean suspendAdmission() {
		synchronized (authority) {
			admissionSuspensions++;
			return pending != null && finish(pending, Status.CANCELLED, null);
		}
	}

	public void resumeAdmission() {
		synchronized (authority) {
			if (admissionSuspensions == 0) { throw new IllegalStateException("Unbalanced admission resume"); }
			admissionSuspensions--;
		}
	}

	/** Final bounded adapter send, serialized with cancellation and lifecycle invalidation. */
	public boolean dispatchIfCurrent(Connection owner, SeededLeafJobClaim claim, Runnable send) {
		Objects.requireNonNull(claim, "claim");
		Objects.requireNonNull(send, "send");
		synchronized (authority) {
			Attempt attempt = pending;
			if (attempt == null || attempt.owner != owner || !current(attempt)
				|| attempt.claim == null || !attempt.claim.equals(claim)) {
				return false;
			}
			send.run();
			return true;
		}
	}

	boolean claimContinuation(Attempt attempt) {
		synchronized (authority) {
			if (attempt.source != offerSource || attempt.continuationClaimed) {
				return false;
			}
			attempt.continuationClaimed = true;
			return true;
		}
	}

	public void reload() {
		synchronized (authority) {
			if (closed || changingLifecycle) {
				return;
			}
			changingLifecycle = true;
			try {
				if (pending != null) {
					finish(pending, Status.STALE_CONTEXT, null);
				}
				if (!closed) {
					validator.invalidateAndReloadAuthority();
				}
			} finally {
				changingLifecycle = false;
			}
		}
	}

	/** Includes interrupt-insensitive recording and validation after logical timeout. */
	public boolean isBusy() {
		synchronized (authority) {
			return admissionSuspensions != 0 || pending != null || recordingOccupied || validator.pendingCount() != 0;
		}
	}

	/**
	 * The caller must run this on the owning generation thread, immediately before
	 * starting the original vanilla fill. A ready result is not an installation:
	 * this checks connection, generation, deadline and one-shot use at that boundary.
	 * The target must perform geometry checks before changing its installation slot.
	 */
	public boolean installIfCurrent(ValidatedOffer offer, RemoteDensityTarget target) {
		Objects.requireNonNull(offer, "offer");
		Objects.requireNonNull(target, "target");
		synchronized (authority) {
			if (offer.used || offer.source != offerSource) {
				return false;
			}
			offer.used = true;
			if (closed || offer.owner != connection || offer.owner.quarantined || !authority.isActive()
				|| offer.generation != authority.contextGeneration()
				|| nanoTime.getAsLong() - offer.deadlineNanos >= 0L) {
				return false;
			}
			target.worldgenAssist$installRemoteDensity(offer.field);
			return true;
		}
	}

	@Override
	public void close() {
		synchronized (authority) {
			if (closed) {
				return;
			}
			closed = true;
			connection = null;
			if (pending != null) {
				finish(pending, Status.CLOSED, null);
			}
			for (Runnable abandoned : recorder.shutdownNow()) {
				Attempt attempt = ((RecordingTask)abandoned).attempt;
				attempt.request = null;
				attempt.task = null;
				recordingOccupied = false;
			}
			watchdog.shutdownNow();
			validator.close();
			authority.stop();
		}
	}

	private void prepare(Attempt attempt) {
		RecordingRequest request;
		synchronized (authority) {
			attempt.runner = Thread.currentThread();
			if (!current(attempt)) {
				return;
			}
			request = attempt.request;
		}
		try {
			recordingHook.beforeRecording(request);
			if (Thread.currentThread().isInterrupted()) {
				throw new java.util.concurrent.CancellationException();
			}
			SeededLeafJobSpecRecorder.RecordedSpec recorded = SeededLeafJobSpecRecorder.record(
				request.dimension(), request.chunkX(), request.chunkZ(), request.randomState(),
				request.settings(), request.noiseSettings(), request.maximumTranscriptEntries());
			CompletionStage<SeededLeafDensityResultEnvelope> response;
			synchronized (authority) {
				if (!current(attempt)) {
					return;
				}
				SeededLeafJobAuthority.IssueResult issue = authority.tryIssue(attempt.owner.ownerId, recorded.spec());
				if (issue.status() != SeededLeafJobAuthority.IssueStatus.ACCEPTED) {
					finish(attempt, Status.AUTHORIZATION_REJECTED, null);
					return;
				}
				AuthorizedSeededLeafJob authorization = issue.authorization().orElseThrow();
				attempt.claim = SeededLeafJobClaim.fromAuthorization(authorization);
				attempt.validationContext = new SeededLeafResultValidationExecutor.ValidationContext(
					request.randomState(), request.settings().value(), request.noiseSettings());
				// Commit dispatch under the lifecycle lock. The adapter must be non-blocking.
				response = Objects.requireNonNull(attempt.exchange.send(attempt.owner, authorization), "response stage");
			}
			response.whenComplete((envelope, error) -> receive(attempt, envelope, error));
		} catch (Throwable error) {
			synchronized (authority) {
				finish(attempt, attempt.claim == null ? Status.RECORDING_FAILED : Status.EXCHANGE_FAILED, null);
			}
			if (error instanceof Error fatal) {
				throw fatal;
			}
		}
	}

	private void receive(Attempt attempt, SeededLeafDensityResultEnvelope envelope, Throwable error) {
		synchronized (authority) {
			if (!current(attempt)) {
				return;
			}
			if (error != null || envelope == null) {
				finish(attempt, Status.EXCHANGE_FAILED, null);
				return;
			}
			// Never let one exchange response consume another attempt's authority claim.
			if (!attempt.claim.jobId().equals(envelope.claim().jobId())) {
				finish(attempt, Status.RESULT_REJECTED, null);
				return;
			}
			try {
				validator.submit(attempt.owner.ownerId, envelope, attempt.validationContext).whenComplete((outcome, failure) -> {
					synchronized (authority) {
						if (!current(attempt)) {
							return;
						}
						if (failure != null || outcome == null
							|| outcome.status() != SeededLeafResultValidationExecutor.Status.VALIDATED) {
							finish(attempt, Status.RESULT_REJECTED, null);
							return;
						}
						try {
							RemoteDensityField field = RemoteDensityField.fromValidated(outcome, authority);
							finish(attempt, Status.READY, new ValidatedOffer(offerSource, attempt, field));
						} catch (RuntimeException exception) {
							finish(attempt, Status.STALE_CONTEXT, null);
						}
					}
				});
			} catch (RuntimeException exception) {
				finish(attempt, Status.RESULT_REJECTED, null);
			}
		}
	}

	private boolean current(Attempt attempt) {
		if (attempt.done || pending != attempt) {
			return false;
		}
		Status invalid = closed ? Status.CLOSED
			: attempt.owner != connection ? Status.DISCONNECTED
			: !authority.isActive() || attempt.generation != authority.contextGeneration() ? Status.STALE_CONTEXT
			: nanoTime.getAsLong() - attempt.deadlineNanos >= 0L ? Status.TIMED_OUT : null;
		if (invalid != null) {
			finish(attempt, invalid, null);
			return false;
		}
		return true;
	}

	private boolean finish(Attempt attempt, Status status, ValidatedOffer offer) {
		if (attempt.done) {
			return false;
		}
		attempt.done = true;
		if (status == Status.RESULT_REJECTED) {
			attempt.owner.quarantined = true;
		} else if (status == Status.TIMED_OUT && attempt.claim != null) {
			attempt.owner.consecutiveTimeouts++;
			if (attempt.owner.consecutiveTimeouts >= 3) {
				attempt.owner.quarantined = true;
			}
		} else if (status == Status.READY) {
			attempt.owner.consecutiveTimeouts = 0;
		}
		// Keep admission closed during reentrant cancellation/completion callbacks.
		if (attempt.timeout != null) {
			attempt.timeout.cancel(false);
			attempt.timeout = null;
		}
		if (status != Status.READY) {
			if (attempt.runner != null) {
				attempt.runner.interrupt();
			} else if (attempt.task != null && recorder.remove(attempt.task)) {
				recordingOccupied = false;
				attempt.task = null;
			}
			if (attempt.claim != null) {
				authority.cancel(attempt.owner.ownerId, attempt.claim.jobId());
				validator.cancelOwner(attempt.owner.ownerId);
				try {
					attempt.exchange.cancel(attempt.owner, attempt.claim);
				} catch (Throwable ignored) {
					// Best effort only: remote cancellation cannot prevent local fallback.
				}
			}
		}
		attempt.request = null;
		attempt.validationContext = null;
		attempt.exchange = null;
		attempt.claim = null;
		if (pending == attempt) {
			pending = null;
		}
		attempt.completion.complete(new Result(status, Optional.ofNullable(offer)));
		return true;
	}

	private static Thread daemon(Runnable runnable, String name) {
		Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}

	/** Server-owned input only; never serializable, logged, or sent to the client. */
	public record RecordingRequest(
		Identifier dimension, int chunkX, int chunkZ, RandomState randomState,
		Holder<NoiseGeneratorSettings> settings, NoiseSettings noiseSettings, int maximumTranscriptEntries
	) {
		public RecordingRequest {
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(randomState, "randomState");
			Objects.requireNonNull(settings, "settings");
			Objects.requireNonNull(noiseSettings, "noiseSettings");
		}
	}

	/**
	 * Trusted adapter contract: send/cancel are non-blocking and thread-safe and
	 * may be called on recorder/watchdog/validation threads. It receives only the
	 * authorized wire model and server-issued connection token, never recording
	 * inputs. A future Fabric adapter must marshal with its own bounded queue and
	 * recheck the connection before sending; none is installed by this class.
	 */
	public interface ClientExchange {
		CompletionStage<SeededLeafDensityResultEnvelope> send(Connection owner, AuthorizedSeededLeafJob job);
		void cancel(Connection owner, SeededLeafJobClaim claim);
	}

	public static final class Connection {
		private final UUID ownerId;
		private boolean quarantined;
		private int consecutiveTimeouts;
		private Connection(UUID ownerId) {
			this.ownerId = ownerId;
		}
		public UUID ownerId() {
			return ownerId;
		}
	}

	public static final class Attempt {
		private final Object source;
		private final Connection owner;
		private final long generation;
		private final long deadlineNanos;
		private final CompletableFuture<Result> completion = new CompletableFuture<>();
		private RecordingRequest request;
		private ClientExchange exchange;
		private SeededLeafJobClaim claim;
		private SeededLeafResultValidationExecutor.ValidationContext validationContext;
		private ScheduledFuture<?> timeout;
		private Thread runner;
		private RecordingTask task;
		private boolean done;
		private boolean continuationClaimed;

		private Attempt(Object source, Connection owner, long generation, long deadlineNanos) {
			this.source = source;
			this.owner = owner;
			this.generation = generation;
			this.deadlineNanos = deadlineNanos;
		}

		/** A minimal stage prevents consumers from completing the internal promise. */
		public CompletionStage<Result> completion() {
			return completion.minimalCompletionStage();
		}
	}

	private final class RecordingTask implements Runnable {
		private final Attempt attempt;
		private RecordingTask(Attempt attempt) {
			this.attempt = attempt;
		}

		@Override
		public void run() {
			try {
				prepare(attempt);
			} finally {
				synchronized (authority) {
					attempt.request = null;
					attempt.runner = null;
					attempt.task = null;
					recordingOccupied = false;
				}
			}
		}
	}

	public enum Status {
		READY, OWNER_INACTIVE, WORKER_QUARANTINED, BUSY, RECORDING_FAILED, AUTHORIZATION_REJECTED,
		EXCHANGE_FAILED, RESULT_REJECTED, TIMED_OUT, CANCELLED, DISCONNECTED, STALE_CONTEXT, CLOSED
	}

	public record Result(Status status, Optional<ValidatedOffer> offer) {
		public Result {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(offer, "offer");
			if ((status == Status.READY) != offer.isPresent()) {
				throw new IllegalArgumentException("Only a ready result carries an installation offer");
			}
		}
	}

	/** Single-use server-local capability; no transcript, seed, or authentication material. */
	public static final class ValidatedOffer {
		private final Object source;
		private final Connection owner;
		private final long generation;
		private final long deadlineNanos;
		private final RemoteDensityField field;
		private boolean used;
		private ValidatedOffer(Object source, Attempt attempt, RemoteDensityField field) {
			this.source = source;
			owner = attempt.owner;
			generation = attempt.generation;
			deadlineNanos = attempt.deadlineNanos;
			this.field = field;
		}
		public UUID jobId() {
			return field.jobId();
		}
	}

	@FunctionalInterface
	interface RecordingHook {
		void beforeRecording(RecordingRequest request);
	}
}
