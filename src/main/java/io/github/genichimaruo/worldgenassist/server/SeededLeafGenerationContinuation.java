package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Integration seam for the original vanilla fill, used only by the public fixture.
 * An adapter must supply the owning generation executor, the
 * eligible cached target and the untouched vanilla operation exactly once.
 */
public final class SeededLeafGenerationContinuation {
	private SeededLeafGenerationContinuation() {
	}

	/**
	 * Every remote failure uses the same vanilla operation without a field. A ready
	 * offer first passes the last lifecycle/one-shot gate on the generation executor.
	 * Once vanilla starts, its failure is propagated, never retried after mutation.
	 * Installation is cleared before this returned stage advances the chunk pipeline.
	 * Executor rejection fails the stage; it never runs worldgen on a response thread.
	 */
	public static <T> CompletionStage<T> continueGeneration(
		SeededLeafJobOrchestrator orchestrator,
		SeededLeafJobOrchestrator.Attempt attempt,
		RemoteDensityTarget target,
		Executor generationExecutor,
		Supplier<CompletableFuture<T>> vanilla
	) {
		Objects.requireNonNull(orchestrator, "orchestrator");
		Objects.requireNonNull(attempt, "attempt");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(generationExecutor, "generationExecutor");
		Objects.requireNonNull(vanilla, "vanilla");
		CompletableFuture<T> completion = new CompletableFuture<>();
		if (!orchestrator.claimContinuation(attempt)) {
			completion.completeExceptionally(new IllegalStateException("Foreign or already continued seeded-leaf attempt"));
			return completion.minimalCompletionStage();
		}
		attempt.completion().whenComplete((result, failure) -> {
			try {
				generationExecutor.execute(() -> start(orchestrator, result, target, vanilla, completion));
			} catch (RuntimeException exception) {
				completion.completeExceptionally(exception);
			}
		});
		return completion.minimalCompletionStage();
	}

	private static <T> void start(
		SeededLeafJobOrchestrator orchestrator,
		SeededLeafJobOrchestrator.Result result,
		RemoteDensityTarget target,
		Supplier<CompletableFuture<T>> vanilla,
		CompletableFuture<T> completion
	) {
		UUID installedJob = null;
		try {
			if (result != null && result.offer().isPresent()) {
				SeededLeafJobOrchestrator.ValidatedOffer offer = result.offer().orElseThrow();
				try {
					if (orchestrator.installIfCurrent(offer, target)) {
						installedJob = offer.jobId();
					}
				} catch (RuntimeException rejectedInstallation) {
					// No vanilla mutation has started. If cleanup itself fails, stop.
					target.worldgenAssist$clearRemoteDensity(offer.jobId());
				}
			}
			CompletableFuture<T> generated = Objects.requireNonNull(vanilla.get(), "vanilla generation future");
			UUID jobToClear = installedJob;
			generated.whenComplete((value, error) -> complete(target, jobToClear, completion, value, error));
		} catch (Throwable error) {
			complete(target, installedJob, completion, null, error);
			if (error instanceof Error fatal) {
				throw fatal;
			}
		}
	}

	private static <T> void complete(
		RemoteDensityTarget target, UUID installedJob, CompletableFuture<T> completion, T value, Throwable error
	) {
		Throwable failure = error;
		if (installedJob != null) {
			try {
				target.worldgenAssist$clearRemoteDensity(installedJob);
			} catch (Throwable cleanupFailure) {
				if (failure == null) {
					failure = cleanupFailure;
				} else if (failure != cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
		}
		if (failure == null) {
			completion.complete(value);
		} else {
			completion.completeExceptionally(failure);
		}
	}
}
