package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
		return SCHEDULER_ID;
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
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(chunkPos, "chunkPos");
		Objects.requireNonNull(fallbackExecutor, "fallbackExecutor");
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
				currentState.pool().execute(() -> {
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
				});
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
		return new ExecutorState(pool, new Semaphore(admissionCapacity));
	}

	private static LocalWorldgenTaskBackend createConfiguredInstance() {
		NoiseStageBackendConfig config = NoiseStageBackendConfig.current();
		return new LocalWorldgenTaskBackend(
			config.workerThreads(),
			queueCapacityForWorkers(config.workerThreads(), config.queuedTasksPerWorker()),
			THREAD_PREFIX
		);
	}

	private record ExecutorState(ForkJoinPool pool, Semaphore admissionPermits) {
		int queuedTasks() {
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
