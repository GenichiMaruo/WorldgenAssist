package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import net.minecraft.world.level.ChunkPos;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;

public final class LocalWorldgenTaskBackend implements WorldgenTaskBackend {
	public static final String SCHEDULER_ID = "fork_join_async";
	private static final String THREAD_PREFIX = "CAWG-LocalWorldgen-";
	private static final LocalWorldgenTaskBackend INSTANCE = createConfiguredInstance();

	private final int workerThreads;
	private final int queueCapacity;
	private final int admissionCapacity;
	private final String threadPrefix;
	private final boolean remoteAware;
	private final AtomicInteger threadIds = new AtomicInteger();
	private final Object lifecycleLock = new Object();
	private final AtomicLong submissionAttempts = new AtomicLong();
	private final AtomicLong acceptedTasks = new AtomicLong();
	private final AtomicLong startedTasks = new AtomicLong();
	private final AtomicLong completedTasks = new AtomicLong();
	private final AtomicLong vanillaFallbacks = new AtomicLong();
	private final AtomicInteger activeTasks = new AtomicInteger();
	private final AtomicInteger intervalPeakActiveTasks = new AtomicInteger();
	private final AtomicInteger intervalPeakAdmittedTasks = new AtomicInteger();
	private volatile ExecutorState executorState;

	LocalWorldgenTaskBackend(int workerThreads, int queueCapacity, String threadPrefix) {
		this(workerThreads, queueCapacity, threadPrefix, false);
	}
	LocalWorldgenTaskBackend(int workerThreads, int queueCapacity, String threadPrefix, boolean remoteAware) {
		if (workerThreads < 1) {
			throw new IllegalArgumentException("workerThreads must be positive");
		}
		if (queueCapacity < 0) {
			throw new IllegalArgumentException("queueCapacity must not be negative");
		}

		this.workerThreads = workerThreads;
		this.queueCapacity = queueCapacity;
		this.admissionCapacity = Math.addExact(workerThreads, queueCapacity);
		this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
		this.remoteAware = remoteAware;
		start();
	}

	public static LocalWorldgenTaskBackend instance() {
		return INSTANCE;
	}

	@Override
	public String id() {
		return "local";
	}

	public String schedulerId() {
		return remoteAware ? "fork_join_remote_ready" : SCHEDULER_ID;
	}

	public int queueCapacity() {
		return queueCapacity;
	}

	static int queueCapacityForWorkers(int workerThreads, int queuedTasksPerWorker) {
		if (workerThreads < 1) {
			throw new IllegalArgumentException("workerThreads must be positive");
		}
		if (queuedTasksPerWorker < 0) {
			throw new IllegalArgumentException("queuedTasksPerWorker must not be negative");
		}
		return Math.multiplyExact(workerThreads, queuedTasksPerWorker);
	}

	static boolean shouldWarnOnFallback(long fallbackCount) {
		return fallbackCount == 1L || fallbackCount % 64L == 0L;
	}

	public void start() {
		synchronized (lifecycleLock) {
			if (executorState == null || executorState.pool().isShutdown()) {
				executorState = createExecutorState();
			}
		}
	}

	@Override
	public Executor executorFor(String stage, ChunkPos chunkPos, Executor fallbackExecutor) {
		return executorFor(stage, chunkPos, fallbackExecutor, () -> RemoteDensityOpportunity.Availability.LOCAL);
	}
	public Executor executorFor(String stage, ChunkPos chunkPos, Executor fallbackExecutor,
		Supplier<RemoteDensityOpportunity.Availability> availability) {
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(chunkPos, "chunkPos");
		Objects.requireNonNull(fallbackExecutor, "fallbackExecutor");
		Objects.requireNonNull(availability, "availability");
		ChunkPos immutablePosition = new ChunkPos(chunkPos.x(), chunkPos.z());

		return command -> {
			long attempt = submissionAttempts.incrementAndGet();
			ExecutorState currentState = executorState;
			boolean permitAcquired = false;
			try {
				if (currentState == null || !currentState.admissionPermits().tryAcquire()) {
					throw new RejectedExecutionException("local backend is stopped or at admission capacity");
				}
				permitAcquired = true;
				int admitted = admissionCapacity - currentState.admissionPermits().availablePermits();
				intervalPeakAdmittedTasks.accumulateAndGet(admitted, Math::max);
				Runnable ownedCommand = () -> {
					int active = activeTasks.incrementAndGet();
					intervalPeakActiveTasks.accumulateAndGet(active, Math::max);
					startedTasks.incrementAndGet();
					try {
						NoiseTaskBenchmarkLogger.runWithExecutionRoute("local_pool", command);
					} finally {
						completedTasks.incrementAndGet();
						activeTasks.decrementAndGet();
						currentState.admissionPermits().release();
					}
				};
				if (currentState.readyQueue() == null) currentState.pool().execute(ownedCommand);
				else synchronized (lifecycleLock) {
					// Shutdown cannot interleave enqueue and scheduling its drainer.
					if (currentState != executorState || currentState.pool().isShutdown()) {
						throw new RejectedExecutionException("cooperative backend lifecycle changed");
					}
					if (currentState.readyQueue().offer(ownedCommand, availability, workerThreads)) {
						currentState.pool().execute(() -> drainReadyQueue(currentState));
					}
				}
				long accepted = acceptedTasks.incrementAndGet();
				WorldgenAssist.LOGGER.debug(
					"[CAWG] backend.local_submitted stage={} chunk={},{} attempt={} accepted={} queue_depth={}",
					stage,
					immutablePosition.x(),
					immutablePosition.z(),
					attempt,
					accepted,
					currentState.queuedTasks()
				);
			} catch (RejectedExecutionException error) {
				if (permitAcquired) {
					currentState.admissionPermits().release();
				}
				long fallbacks = vanillaFallbacks.incrementAndGet();
				if (shouldWarnOnFallback(fallbacks)) {
					WorldgenAssist.LOGGER.warn(
						"[CAWG] backend.fallback_vanilla stage={} chunk={},{} reason=local_executor_rejected fallbacks={}",
						stage,
						immutablePosition.x(),
						immutablePosition.z(),
						fallbacks
					);
				} else {
					WorldgenAssist.LOGGER.debug(
						"[CAWG] backend.fallback_vanilla stage={} chunk={},{} reason=local_executor_rejected fallbacks={}",
						stage,
						immutablePosition.x(),
						immutablePosition.z(),
						fallbacks
					);
				}
				fallbackExecutor.execute(
					() -> NoiseTaskBenchmarkLogger.runWithExecutionRoute("vanilla_fallback", command)
				);
			}
		};
	}

	private void drainReadyQueue(ExecutorState state) {
		Runnable command;
		while ((command = state.readyQueue().poll()) != null) {
			try { command.run(); }
			catch (RuntimeException | Error error) {
				// A failed command must not strand accepted work or its admission permits.
				WorldgenAssist.LOGGER.error("[CAWG] backend.cooperative_command_failed", error);
			}
		}
	}

	public Snapshot snapshot() {
		ExecutorState currentState = executorState;
		int availableAdmissionPermits = currentState == null
			? admissionCapacity
			: currentState.admissionPermits().availablePermits();
		return new Snapshot(
			submissionAttempts.get(),
			acceptedTasks.get(),
			startedTasks.get(),
			completedTasks.get(),
			vanillaFallbacks.get(),
			currentState == null ? 0 : currentState.queuedTasks(),
			currentState == null ? 0 : currentState.pool().getParallelism(),
			currentState == null ? 0 : currentState.pool().getPoolSize(),
			currentState == null ? 0 : currentState.pool().getActiveThreadCount(),
			currentState == null ? 0 : currentState.pool().getRunningThreadCount(),
			admissionCapacity,
			availableAdmissionPermits,
			admissionCapacity - availableAdmissionPermits,
			activeTasks.get()
		);
	}

	SaturationSample sampleSaturationAndResetPeaks() {
		Snapshot snapshot = snapshot();
		int peakActiveTasks = Math.max(
			snapshot.activeTasks(),
			intervalPeakActiveTasks.getAndSet(snapshot.activeTasks())
		);
		int peakAdmittedTasks = Math.max(
			snapshot.admittedTasks(),
			intervalPeakAdmittedTasks.getAndSet(snapshot.admittedTasks())
		);
		return new SaturationSample(snapshot, peakActiveTasks, peakAdmittedTasks);
	}

	@Override
	public void close() {
		ExecutorState stoppedState;
		synchronized (lifecycleLock) {
			stoppedState = executorState;
			executorState = null;
		}
		if (stoppedState != null) {
			if (stoppedState.readyQueue() != null) WorldgenAssist.LOGGER.info(
				"[CAWG] backend.cooperative_summary scheduler={} reordered={} queued={}",
				schedulerId(), stoppedState.readyQueue().reordered(), stoppedState.readyQueue().size());
			stoppedState.pool().shutdown();
		}
		Snapshot snapshot = snapshot();
		WorldgenAssist.LOGGER.info(
			"[CAWG] backend.local_shutdown scheduler={} submitted={} accepted={} started={} completed={} fallbacks={} queued={}",
			schedulerId(),
			snapshot.submissionAttempts(),
			snapshot.acceptedTasks(),
			snapshot.startedTasks(),
			snapshot.completedTasks(),
			snapshot.vanillaFallbacks(),
			snapshot.queuedTasks()
		);
	}

	private ExecutorState createExecutorState() {
		ForkJoinPool pool = new ForkJoinPool(
			workerThreads,
			forkJoinPool -> {
				ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(forkJoinPool);
				thread.setName(threadPrefix + threadIds.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			},
			(thread, error) -> WorldgenAssist.LOGGER.error("[CAWG] backend.local_worker_failed thread={}", thread.getName(), error),
			true
		);
		return new ExecutorState(pool, new Semaphore(admissionCapacity), remoteAware ? new RemoteAwareTerrainQueue() : null);
	}

	private static LocalWorldgenTaskBackend createConfiguredInstance() {
		NoiseStageBackendConfig config = NoiseStageBackendConfig.current();
		return new LocalWorldgenTaskBackend(
			config.workerThreads(),
			queueCapacityForWorkers(config.workerThreads(), config.queuedTasksPerWorker()),
			THREAD_PREFIX, config.mode() == NoiseStageBackendConfig.Mode.COOPERATIVE
		);
	}

	private record ExecutorState(ForkJoinPool pool, Semaphore admissionPermits, RemoteAwareTerrainQueue readyQueue) {
		int queuedTasks() {
			if (readyQueue != null) return readyQueue.size();
			long queued = pool.getQueuedSubmissionCount() + pool.getQueuedTaskCount();
			return (int) Math.min(Integer.MAX_VALUE, queued);
		}
	}

	public record Snapshot(
		long submissionAttempts,
		long acceptedTasks,
		long startedTasks,
		long completedTasks,
		long vanillaFallbacks,
		int queuedTasks,
		int parallelism,
		int poolSize,
		int activeThreads,
		int runningThreads,
		int admissionCapacity,
		int availableAdmissionPermits,
		int admittedTasks,
		int activeTasks
	) {
	}

	record SaturationSample(Snapshot snapshot, int peakActiveTasks, int peakAdmittedTasks) {
	}
}
