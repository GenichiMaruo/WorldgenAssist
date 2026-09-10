package io.github.genichimaruo.worldgenassist.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

public final class RemoteJobCoordinator {
	static final int MAX_CONSECUTIVE_TIMEOUTS = 3;
	private static final Duration TERMINAL_RETENTION = Duration.ofSeconds(30);

	private final boolean remoteEnabled;
	private final WorkerRegistry workers;
	private final PendingTerrainJobRegistry pendingJobs;
	private final RemoteJobSender sender;
	private final Map<UUID, Attempt> attempts = new HashMap<>();
	private final Map<UUID, Integer> consecutiveTimeouts = new HashMap<>();
	private final Set<UUID> decodingResults = new HashSet<>();
	private final Set<UUID> quarantinedOwners = new HashSet<>();

	public RemoteJobCoordinator(RemoteWorldgenConfig config, RemoteJobSender sender) {
		this(
			config.remoteExecutionEnabled(),
			new WorkerRegistry(config.maxInFlightJobs()),
			new PendingTerrainJobRegistry(
				config.maxInFlightJobs(),
				config.maxInFlightJobs(),
				config.jobTimeout(),
				TERMINAL_RETENTION
			),
			sender
		);
	}

	RemoteJobCoordinator(
		boolean remoteEnabled,
		WorkerRegistry workers,
		PendingTerrainJobRegistry pendingJobs,
		RemoteJobSender sender
	) {
		this.remoteEnabled = remoteEnabled;
		this.workers = Objects.requireNonNull(workers, "workers");
		this.pendingJobs = Objects.requireNonNull(pendingJobs, "pendingJobs");
		this.sender = Objects.requireNonNull(sender, "sender");
	}

	public WorkerAcceptedPayload handleHello(UUID ownerId, WorkerHelloPayload hello) {
		WorkerAcceptedPayload response;
		synchronized (this) {
			if (quarantinedOwners.contains(ownerId)) {
				return new WorkerAcceptedPayload(
					WorldgenProtocolVersion.CURRENT,
					WorkerAcceptedPayload.Status.REMOTE_DISABLED,
					0
				);
			}
			response = workers.acceptHello(ownerId, hello, remoteEnabled);
		}
		if (!response.accepted()) {
			cancelIdentities(
				ownerId,
				pendingJobs.cancelAllForOwner(ownerId),
				new CancellationException("Remote worker registration was rejected: " + response.status()),
				false
			);
		}
		return response;
	}

	public Optional<UUID> soleWorkerOwner() {
		return workers.soleWorkerOwner();
	}

	public Optional<Submission> trySubmit(
		Identifier dimension,
		int chunkX,
		int chunkZ,
		WorldgenContextFingerprint contextFingerprint,
		Function<TerrainJobIdentity, TerrainDensityJob> jobFactory
	) {
		return trySubmitForOwner(null, dimension, chunkX, chunkZ, contextFingerprint, jobFactory);
	}

	public Optional<Submission> trySubmitForOwner(
		UUID requiredOwnerId,
		Identifier dimension,
		int chunkX,
		int chunkZ,
		WorldgenContextFingerprint contextFingerprint,
		Function<TerrainJobIdentity, TerrainDensityJob> jobFactory
	) {
		if (requiredOwnerId != null) {
			Objects.requireNonNull(requiredOwnerId, "requiredOwnerId");
		}
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(contextFingerprint, "contextFingerprint");
		Objects.requireNonNull(jobFactory, "jobFactory");
		if (!remoteEnabled) {
			return Optional.empty();
		}

		WorkerRegistry.Lease lease;
		TerrainDensityJob job;
		Attempt attempt;
		synchronized (this) {
			Optional<WorkerRegistry.Lease> acquired = workers.tryAcquireSoleWorker(requiredOwnerId);
			if (acquired.isEmpty()) {
				return Optional.empty();
			}
			lease = acquired.get();
			if (!sender.canSend(lease.ownerId())) {
				workers.release(lease.ownerId());
				return Optional.empty();
			}

			PendingTerrainJobRegistry.RegistrationResult registration = pendingJobs.tryRegister(
				lease.ownerId(),
				dimension,
				chunkX,
				chunkZ,
				contextFingerprint
			);
			if (registration.status() != PendingTerrainJobRegistry.RegistrationStatus.ACCEPTED) {
				workers.release(lease.ownerId());
				return Optional.empty();
			}

			TerrainJobIdentity identity = registration.identity().orElseThrow();
			try {
				job = Objects.requireNonNull(jobFactory.apply(identity), "jobFactory returned null");
				if (!identity.equals(job.identity())) {
					throw new IllegalArgumentException("jobFactory changed the server-issued identity");
				}
			} catch (RuntimeException | Error error) {
				pendingJobs.cancel(lease.ownerId(), identity.jobId());
				workers.release(lease.ownerId());
				throw error;
			}
			attempt = new Attempt(lease.ownerId(), job);
			attempts.put(identity.jobId(), attempt);
		}

		try {
			sender.sendJob(lease.ownerId(), new TerrainJobRequestPayload(job));
		} catch (RuntimeException | Error error) {
			failSend(attempt, error);
			return Optional.empty();
		}
		return Optional.of(new Submission(attempt.ownerId, job, attempt.result));
	}

	public PendingTerrainJobRegistry.ResponseStatus handleResult(UUID ownerId, TerrainDensityResult result) {
		Objects.requireNonNull(result, "result");
		PendingTerrainJobRegistry.ResponseStatus status = beginResult(ownerId, result.identity());
		return status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED
			? completeClaimedResult(ownerId, result)
			: status;
	}

	public synchronized PendingTerrainJobRegistry.ResponseStatus beginResult(UUID ownerId, TerrainJobIdentity identity) {
		PendingTerrainJobRegistry.ResponseStatus status = pendingJobs.inspectResponse(ownerId, identity);
		if (status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED && !decodingResults.add(identity.jobId())) {
			return PendingTerrainJobRegistry.ResponseStatus.DUPLICATE;
		}
		return status;
	}

	public PendingTerrainJobRegistry.ResponseStatus completeClaimedResult(UUID ownerId, TerrainDensityResult result) {
		Objects.requireNonNull(result, "result");
		Attempt attempt = null;
		PendingTerrainJobRegistry.ResponseStatus status;
		synchronized (this) {
			if (!decodingResults.remove(result.identity().jobId())) {
				return pendingJobs.inspectResponse(ownerId, result.identity());
			}
			status = pendingJobs.evaluateResponse(ownerId, result.identity());
			if (status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED) {
				attempt = attempts.remove(result.identity().jobId());
				workers.release(ownerId);
				consecutiveTimeouts.remove(ownerId);
			}
		}
		if (attempt != null) {
			attempt.result.complete(result);
		}
		return status;
	}

	public PendingTerrainJobRegistry.ResponseStatus failClaimedResult(
		UUID ownerId,
		TerrainJobIdentity identity,
		Throwable error
	) {
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(error, "error");
		Attempt attempt = null;
		PendingTerrainJobRegistry.ResponseStatus status;
		synchronized (this) {
			if (!decodingResults.remove(identity.jobId())) {
				return pendingJobs.inspectResponse(ownerId, identity);
			}
			status = pendingJobs.evaluateResponse(ownerId, identity);
			if (status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED) {
				attempt = attempts.remove(identity.jobId());
				workers.release(ownerId);
			}
		}
		if (attempt != null) {
			attempt.result.completeExceptionally(error);
		}
		return status;
	}

	public PendingTerrainJobRegistry.ResponseStatus handleFailure(UUID ownerId, TerrainJobFailurePayload failure) {
		Objects.requireNonNull(failure, "failure");
		Attempt attempt = null;
		PendingTerrainJobRegistry.ResponseStatus status;
		synchronized (this) {
			status = pendingJobs.evaluateResponse(ownerId, failure.identity());
			if (status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED) {
				attempt = attempts.remove(failure.identity().jobId());
				workers.release(ownerId);
			}
		}
		if (attempt != null) {
			attempt.result.completeExceptionally(new RemoteWorkerException(failure.reason()));
		}
		return status;
	}

	public int expireTimedOut() {
		return expireTimedOut(true);
	}

	int expireTimedOut(boolean sendCancel) {
		List<Completion> completions = new ArrayList<>();
		Set<UUID> newlyQuarantined = new HashSet<>();
		synchronized (this) {
			for (TerrainJobIdentity identity : pendingJobs.expireTimedOut()) {
				decodingResults.remove(identity.jobId());
				Attempt attempt = attempts.remove(identity.jobId());
				if (attempt != null) {
					workers.release(attempt.ownerId);
					int timeoutCount = consecutiveTimeouts.merge(attempt.ownerId, 1, Integer::sum);
					if (timeoutCount >= MAX_CONSECUTIVE_TIMEOUTS && quarantinedOwners.add(attempt.ownerId)) {
						workers.remove(attempt.ownerId);
						newlyQuarantined.add(attempt.ownerId);
					}
					completions.add(new Completion(
						attempt,
						new TimeoutException("Remote terrain job timed out: " + identity.jobId()),
						sendCancel
					));
				}
			}
		}
		completeFailures(completions);
		for (UUID ownerId : newlyQuarantined) {
			int cancelled = cancelIdentities(
				ownerId,
				pendingJobs.cancelAllForOwner(ownerId),
				new CancellationException("Remote worker exceeded the consecutive-timeout limit: " + ownerId),
				false
			);
			WorldgenAssist.LOGGER.warn(
				"[CAWG] worker.quarantined owner={} reason=consecutive_timeouts threshold={} cancelled_jobs={}",
				ownerId,
				MAX_CONSECUTIVE_TIMEOUTS,
				cancelled
			);
		}
		return completions.size();
	}

	public synchronized int quarantine(UUID ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		quarantinedOwners.add(ownerId);
		workers.remove(ownerId);
		return cancelIdentities(
			ownerId,
			pendingJobs.cancelAllForOwner(ownerId),
			new CancellationException("Remote worker was quarantined after a validation failure: " + ownerId),
			false
		);
	}

	public int disconnect(UUID ownerId) {
		synchronized (this) {
			quarantinedOwners.remove(ownerId);
			consecutiveTimeouts.remove(ownerId);
			workers.remove(ownerId);
		}
		return cancelIdentities(
			ownerId,
			pendingJobs.cancelAllForOwner(ownerId),
			new CancellationException("Remote worker disconnected: " + ownerId),
			false
		);
	}

	public int shutdown() {
		int cancelled = cancelAll("Server stopped", false);
		workers.clear();
		synchronized (this) {
			quarantinedOwners.clear();
			consecutiveTimeouts.clear();
		}
		return cancelled;
	}

	public int cancelAllForReload() {
		return cancelAll("Data pack reload invalidated the worldgen context", true);
	}

	private int cancelAll(String reason, boolean sendCancel) {
		List<TerrainJobIdentity> identities = pendingJobs.cancelAll();
		List<Completion> completions = new ArrayList<>();
		synchronized (this) {
			for (TerrainJobIdentity identity : identities) {
				decodingResults.remove(identity.jobId());
				Attempt attempt = attempts.remove(identity.jobId());
				if (attempt != null) {
					workers.release(attempt.ownerId);
					completions.add(new Completion(attempt, new CancellationException(reason), sendCancel));
				}
			}
		}
		completeFailures(completions);
		return completions.size();
	}

	public synchronized int pendingCount() {
		return attempts.size();
	}

	private void failSend(Attempt attempt, Throwable error) {
		synchronized (this) {
			Attempt removed = attempts.remove(attempt.job.identity().jobId());
			if (removed == null) {
				return;
			}
			pendingJobs.cancel(attempt.ownerId, attempt.job.identity().jobId());
			workers.release(attempt.ownerId);
		}
		attempt.result.completeExceptionally(error);
	}

	private int cancelIdentities(UUID ownerId, List<TerrainJobIdentity> identities, Throwable error, boolean sendCancel) {
		List<Completion> completions = new ArrayList<>();
		synchronized (this) {
			for (TerrainJobIdentity identity : identities) {
				decodingResults.remove(identity.jobId());
				Attempt attempt = attempts.remove(identity.jobId());
				if (attempt != null) {
					workers.release(ownerId);
					completions.add(new Completion(attempt, error, sendCancel));
				}
			}
		}
		completeFailures(completions);
		return completions.size();
	}

	private void completeFailures(List<Completion> completions) {
		for (Completion completion : completions) {
			if (completion.sendCancel && sender.canSend(completion.attempt.ownerId)) {
				try {
					sender.sendCancel(
						completion.attempt.ownerId,
						new TerrainJobCancelPayload(completion.attempt.job.identity())
					);
				} catch (RuntimeException error) {
					// The local fallback future must complete even if the cancellation packet cannot be sent.
				}
			}
			completion.attempt.result.completeExceptionally(completion.error);
		}
	}

	public record Submission(UUID ownerId, TerrainDensityJob job, CompletableFuture<TerrainDensityResult> result) {
		public Submission {
			Objects.requireNonNull(ownerId, "ownerId");
			Objects.requireNonNull(job, "job");
			Objects.requireNonNull(result, "result");
		}
	}

	private static final class Attempt {
		private final UUID ownerId;
		private final TerrainDensityJob job;
		private final CompletableFuture<TerrainDensityResult> result = new CompletableFuture<>();

		private Attempt(UUID ownerId, TerrainDensityJob job) {
			this.ownerId = ownerId;
			this.job = job;
		}
	}

	private record Completion(Attempt attempt, Throwable error, boolean sendCancel) {
	}
}
