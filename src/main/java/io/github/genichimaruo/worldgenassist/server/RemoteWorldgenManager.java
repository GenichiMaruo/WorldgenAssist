package io.github.genichimaruo.worldgenassist.server;

import java.security.SecureRandom;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.function.Consumer;

import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseSettings;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

public final class RemoteWorldgenManager {
	private static RemoteWorldgenManager instance;

	private final RemoteWorldgenConfig config;
	private final RemoteJobCoordinator coordinator;
	private final ScheduledExecutorService timeoutWatchdog;
	private final AdaptiveDemandWait demandWait;
	private final java.util.concurrent.atomic.AtomicReference<PrefetchDispatch> prefetchDispatchQueued = new java.util.concurrent.atomic.AtomicReference<>();
	private final ThreadPoolExecutor resultDecoder;
	private final ThreadPoolExecutor validationExecutor;
	private final BoundedRemotePreparation<RemoteDensityValidator.Prepared, TerrainDensityResult> preparation;
	private final RemotePipelineOptions pipelineOptions = RemotePipelineOptions.current();
	private final int prefetchLookahead = RemotePipelineOptions.prefetchLookahead();
	private final int ownerWindow = RemotePipelineOptions.ownerWindow();
	private final GenerationPrefetchQueue generationCandidates;
	private final QueuedTerrainAdmission<RemoteDensityResultCache.Key, RemoteWorldgenEligibility.SpeculativeContext> queuedTerrain;
	private final Set<RemoteDensityResultCache.Key> dispatching = ConcurrentHashMap.newKeySet();
	private final RemoteDensityResultCache resultCache;
	private final PlayerOwnedChunkPredictor predictor = new PlayerOwnedChunkPredictor();
	private final Map<ResourceKey<Level>, WorldgenContextFingerprint> contextFingerprints = new ConcurrentHashMap<>();
	private final Set<ResourceKey<Level>> fingerprintPreparation = ConcurrentHashMap.newKeySet();
	private final Map<UUID, ResultTransferMetrics> transferMetrics = new ConcurrentHashMap<>();
	private final Map<RemoteDensityResultCache.Key, PredictionAttempt> predictedJobs = new ConcurrentHashMap<>();
	private final AtomicLong cacheGeneration = new AtomicLong();
	private final Object resultStateLock = new Object();
	private final StartedTerrain<RemoteDensityResultCache.Key> startedTerrain;
	private final SecureRandom validationRandom = new SecureRandom();
	private final CompleteTerrainAuditPolicy<CompleteAuditContext> completeAudits;
	/** Only the owned validation executor computes; lifecycle mutations use resultStateLock. */
	private final Map<CompleteAuditContext, io.github.genichimaruo.worldgenassist.common.PrivateTerrainComputer> completeAuditComputers = new java.util.HashMap<>();
	private long predictionTicks;
	private volatile List<PlayerChunkDemand> demands = List.of();
	private final Map<UUID, Long> ownerGenerations = new ConcurrentHashMap<>();
	private final AtomicLong nextOwnerGeneration = new AtomicLong();
	private final boolean diagnostics = Boolean.getBoolean("worldgen_assist.remote.diagnostics");
	private final boolean traceJobs = Boolean.parseBoolean(System.getProperty("worldgen_assist.remote.trace_jobs", Boolean.toString(diagnostics)));
	private final LongAdder loadHints = new LongAdder();
	private final LongAdder taskHints = new LongAdder();
	private final LongAdder dependencyHints = new LongAdder();
	private final LongAdder capacityLimitedRefills = new LongAdder();
	private void trace(String format,Object... arguments) {
		if(traceJobs)WorldgenAssist.LOGGER.info(format,arguments);
	}
	private final LongAdder noOwnerFallbacks = new LongAdder();
	private final LongAdder ineligibleFallbacks = new LongAdder();
	private final LongAdder noCapacityFallbacks = new LongAdder();
	private final LongAdder submittedJobs = new LongAdder();
	private long diagnosticTicks;
	private final LongAdder prefetchSent = new LongAdder();
	private final LongAdder prefetchUsed = new LongAdder();
	private final LongAdder demandWaitExpired = new LongAdder();
	private final LongAdder lateResultsDiscarded = new LongAdder();
	private volatile MinecraftServer server;

	private RemoteWorldgenManager(RemoteWorldgenConfig config, RemoteJobSender sender) {
		this.config = config;
		this.coordinator = new RemoteJobCoordinator(config, sender);
		this.resultCache = new RemoteDensityResultCache(config.cacheEntries(), config.jobTimeout().toNanos());
		this.startedTerrain = new StartedTerrain<>(16_384, config.jobTimeout().toNanos());
		this.generationCandidates = new GenerationPrefetchQueue(config.maxInFlightJobs() * 16);
		this.queuedTerrain = new QueuedTerrainAdmission<>(config.maxInFlightJobs());
		WorldgenAssist.LOGGER.info("[CAWG] prefetch.policy lookahead={} capacity={}", prefetchLookahead, config.maxInFlightJobs() * 16);
		WorldgenAssist.LOGGER.info("[CAWG] pipeline.window owner={} total={} refill_watermark={}", ownerWindow, config.maxInFlightJobs(), RemotePipelineOptions.refillWatermark(config.maxInFlightJobs(), ownerWindow));
		this.validationExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(config.maxInFlightJobs()), task -> {
				Thread thread = new Thread(task, "CAWG-RemoteValidation"); thread.setDaemon(true); return thread;
			}, new ThreadPoolExecutor.AbortPolicy());
		this.preparation = new BoundedRemotePreparation<>(config.maxInFlightJobs(), validationExecutor);
		this.completeAudits = new CompleteTerrainAuditPolicy<>(config.maxInFlightJobs(), () -> validationRandom.nextInt(8));
		this.timeoutWatchdog = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "CAWG-RemoteTimeout");
			thread.setDaemon(true);
			return thread;
		});
		this.demandWait = new AdaptiveDemandWait(timeoutWatchdog, System::nanoTime);
		this.resultDecoder = new ThreadPoolExecutor(
			Math.min(2, config.maxInFlightJobs()),
			Math.min(2, config.maxInFlightJobs()),
			0L,
			TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(config.maxInFlightJobs()),
			task -> {
				Thread thread = new Thread(task, "CAWG-RemoteDecode");
				thread.setDaemon(true);
				return thread;
			},
			new ThreadPoolExecutor.AbortPolicy()
		);
	}

	public static synchronized void register(RemoteWorldgenConfig config, RemoteJobSender sender, Consumer<RemoteWorldgenManager> hooks) {
		if (instance != null) {
			throw new IllegalStateException("RemoteWorldgenManager is already registered");
		}
		RemoteWorldgenManager manager = new RemoteWorldgenManager(config, sender);
		instance = manager;
		hooks.accept(manager);
		manager.startTimeoutWatchdog();
	}

	public static CompletableFuture<ChunkAccess> generateNoiseOrFallback(
		WorldGenContext context,
		ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk,
		Supplier<CompletableFuture<ChunkAccess>> localFallback
	) {
		RemoteWorldgenManager manager = instance;
		if (manager == null) return invokeFallback(localFallback);
		long generation=manager.cacheGeneration.get();
		var result=manager.generate(context,step,chunks,chunk,localFallback);
		if (!manager.config.remoteExecutionEnabled()) return result;
		return result.whenComplete((generated,error)->{
			if (generation!=manager.cacheGeneration.get()) return;
			manager.discardAhead(context.level().dimension().identifier(),chunk.getPos(),generation);
			manager.requestPrefetchDispatch();
		});
	}

	public static void observeGeneration(WorldGenContext context, ChunkAccess chunk) {
		observeCandidate(context,chunk,false);
	}
	public static boolean observesChunkLoads() {
		RemoteWorldgenManager manager=instance;
		return manager!=null && manager.config.remoteExecutionEnabled() && manager.pipelineOptions.prefetch()
			&& manager.config.cacheEntries()>0;
	}
	public static void observeChunkLoaded(WorldGenContext context,ChunkAccess chunk) {
		if(chunk.getPersistedStatus().isBefore(net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN))
			observeCandidate(context,chunk,true);
	}
	public static void observeTerrainRequested(WorldGenContext context,
		net.minecraft.world.level.chunk.status.ChunkStatus target,net.minecraft.server.level.ChunkGenerationTask task) {
		RemoteWorldgenManager manager=instance;
		if(manager==null || !observesChunkLoads() || context.level().getServer()!=manager.server
			|| context.generator().getClass()!=net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class) return;
		if (target.isBefore(net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN)) return;
		var center = task.getCenter().getPos();
		boolean[] added = {false};
		java.util.function.Consumer<GenerationChunkHolder> observe = holder -> {
			if (!GenerationPrefetchQueue.targetsUnfinishedTerrain(target, holder.getPersistedStatus())) return;
			ChunkPos pos = holder.getPos();
			PlayerChunkDemand.selectGeneration(manager.demands, context.level().dimension().identifier(), pos.x(), pos.z())
				.ifPresent(demand -> {
					manager.taskHints.increment();
					if (!pos.equals(center)) manager.dependencyHints.increment();
					added[0] |= manager.generationCandidates.offer(demand, pos.x(), pos.z(), manager.cacheGeneration.get(),
						manager.ownerGenerations.getOrDefault(demand.ownerId(), -1L),
						System.nanoTime() + manager.config.jobTimeout().toNanos(), true, !pos.equals(center));
				});
		};
		if (task instanceof TerrainTaskHintSource source) source.worldgenAssist$visitTerrainCandidates(observe);
		else observe.accept(task.getCenter());
		if (added[0]) manager.requestPrefetchDispatch();
	}
	private static void observeCandidate(WorldGenContext context,ChunkAccess chunk,boolean fromLoad) {
		RemoteWorldgenManager manager = instance;
		if (manager == null || !manager.config.remoteExecutionEnabled() || !manager.pipelineOptions.prefetch()
			|| context.level().getServer()!=manager.server
			|| manager.config.cacheEntries() == 0 || chunk.isOldNoiseGeneration() || chunk.getBelowZeroRetrogen() != null
			|| context.generator().getClass() != net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class) return;
		ChunkPos pos = chunk.getPos();
		PlayerChunkDemand.selectGeneration(manager.demands, context.level().dimension().identifier(), pos.x(), pos.z())
			.ifPresent(demand -> {
				if(fromLoad)manager.loadHints.increment();
				manager.generationCandidates.offer(demand, pos.x(), pos.z(),
					manager.cacheGeneration.get(), manager.ownerGenerations.getOrDefault(demand.ownerId(), -1L),
					System.nanoTime() + manager.config.jobTimeout().toNanos());
				manager.requestPrefetchDispatch();
			});
	}

	public static void duringSynchronousChunkWait(Runnable wait) {
		RemoteWorldgenManager manager = instance;
		MinecraftServer current = manager == null ? null : manager.server;
		if (current == null || !current.isSameThread()) { wait.run(); return; }
		manager.coordinator.duringSynchronousChunkWait(() -> {
			manager.preparation.cancelAll();
			wait.run();
		});
	}

	public static MinecraftServer activeServer() {
		RemoteWorldgenManager manager = instance;
		return manager == null ? null : manager.server;
	}

	public static void onReloadStart() {
		RemoteWorldgenManager manager = instance;
		if (manager != null) manager.onDatapackReload();
	}

	public WorkerAcceptedPayload handleHello(UUID ownerId, WorkerHelloPayload payload) {
			WorkerAcceptedPayload response = coordinator.handleHello(ownerId, payload);
			if (response.accepted()) {
				ownerGenerations.computeIfAbsent(ownerId, ignored -> nextOwnerGeneration.incrementAndGet());
			} else { invalidateOwner(ownerId); }
			WorldgenAssist.LOGGER.info(
				"[CAWG] worker.register owner={} status={} requested_parallel={} version={}",
				ownerId,
				response.status(),
				payload.maxParallelJobs(),
				payload.implementationVersion()
			);
			return response;
	}

	public void handleResult(UUID ownerId, TerrainJobResultPayload payload) {
		acceptResult(ownerId, payload, System.nanoTime(), () -> true, "main");
	}

	/** Netty ingress only offers to a bounded decoder; it never reads player/world state. */
	public void receiveResultFromNetwork(UUID ownerId, TerrainJobResultPayload payload, long receivedNanos,
		java.util.function.BooleanSupplier currentConnection) {
		try {
			resultDecoder.execute(() -> acceptResult(ownerId, payload, receivedNanos, currentConnection, "network"));
		} catch (RejectedExecutionException error) {
			WorldgenAssist.LOGGER.warn("[CAWG] job.ingress_rejected id={} reason=decoder_capacity", payload.result().identity().jobId());
		}
	}

	private void acceptResult(UUID ownerId, TerrainJobResultPayload payload, long receivedNanos,
		java.util.function.BooleanSupplier currentConnection, String path) {
			if (!currentConnection.getAsBoolean()) return;
			long handlerStarted = System.nanoTime();
			TerrainDensityResultEnvelope envelope = payload.result();
			PendingTerrainJobRegistry.ResponseStatus status = coordinator.beginResult(ownerId, envelope.identity());
			if (status != PendingTerrainJobRegistry.ResponseStatus.ACCEPTED) {
				WorldgenAssist.LOGGER.warn(
					"[CAWG] job.result_rejected id={} owner={} status={}",
					envelope.identity().jobId(),
					ownerId,
					status
				);
				return;
			}
			try {
				trace("[CAWG] job.result_ingress id={} path={} ingress_queue_ms={} claim_ms={}",
					envelope.identity().jobId(), path, (handlerStarted - receivedNanos) / 1_000_000.0,
					(System.nanoTime() - handlerStarted) / 1_000_000.0);
				if (path.equals("network")) decodeResult(ownerId, envelope, receivedNanos, currentConnection);
				else resultDecoder.execute(() -> decodeResult(ownerId, envelope, receivedNanos, currentConnection));
			} catch (RejectedExecutionException error) {
				PendingTerrainJobRegistry.ResponseStatus failureStatus = coordinator.failClaimedResult(
					ownerId,
					envelope.identity(),
					error
				);
				WorldgenAssist.LOGGER.warn(
					"[CAWG] job.decode_rejected id={} owner={} status={} queue_size={}",
					envelope.identity().jobId(),
					ownerId,
					failureStatus,
					resultDecoder.getQueue().size()
				);
			}
	}

	public boolean acceptsFragment(UUID ownerId, TerrainJobIdentity identity) {
		return config.remoteExecutionEnabled()
			&& coordinator.inspectResult(ownerId, identity) == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED;
	}

	public void handleFailure(UUID ownerId, TerrainJobFailurePayload payload) {
			PendingTerrainJobRegistry.ResponseStatus status = coordinator.handleFailure(ownerId, payload);
			WorldgenAssist.LOGGER.info(
				"[CAWG] job.client_rejected id={} owner={} reason={} status={}",
				payload.identity().jobId(),
				ownerId,
				payload.reason(),
				status
			);
	}

	public void onDisconnect(MinecraftServer currentServer, ServerPlayer disconnected) {
			ServerPlayer connected = currentServer.getPlayerList().getPlayer(disconnected.getUUID());
			if (connected != null && connected != disconnected) { return; }
			invalidateOwner(disconnected.getUUID());
			int cancelled = coordinator.disconnect(disconnected.getUUID());
			predictor.remove(disconnected.getUUID());
			if (cancelled > 0) {
				WorldgenAssist.LOGGER.info("[CAWG] worker.disconnect owner={} cancelled_jobs={}", disconnected.getUUID(), cancelled);
			}
	}

	public void onDimensionChanged(ServerPlayer player, ResourceKey<Level> origin, ResourceKey<Level> destination) {
			invalidateOwner(player.getUUID(), true);
			predictor.remove(player.getUUID());
			int cancelled = coordinator.cancelForDimensionChange(player.getUUID());
			WorldgenAssist.LOGGER.info("[CAWG] worker.dimension_changed owner={} from={} to={} cancelled_jobs={}",
				player.getUUID(), origin.identifier(), destination.identifier(), cancelled);
	}

	public void onServerStarting(MinecraftServer currentServer) {
			if (diagnostics) WorldgenAssist.LOGGER.info("[CAWG] server.cpu_context available_processors={}",
				Runtime.getRuntime().availableProcessors());
			demands = List.of();
			ownerGenerations.clear();
			server = currentServer;
			contextFingerprints.clear();
			clearResultState("server_starting");
	}

	public void onDatapackReload() {
			demands = List.of();
			int cancelled = coordinator.cancelAllForReload();
			contextFingerprints.clear();
			clearResultState("datapack_reload");
			if (cancelled > 0) {
				WorldgenAssist.LOGGER.info("[CAWG] datapack_reload cancelled_jobs={}", cancelled);
			}
	}

	public void onServerStopping() {
			if (diagnostics) {
			MinecraftServer stopping=server;
			Thread dump = new Thread(() -> {
				try { Thread.sleep(15_000L); }
				catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
				if (stopping!=null && server == stopping) {
					stopping.schedule(stopping.wrapRunnable(() -> ShutdownWorkDiagnostic.record(stopping)));
					for (var thread : java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
						WorldgenAssist.LOGGER.warn("[CAWG] shutdown.thread_dump thread={} state={} owner={} stack={}",
							thread.getThreadName(), thread.getThreadState(), thread.getLockOwnerName(),
							java.util.Arrays.toString(thread.getStackTrace()));
					}
				}
			}, "CAWG-ShutdownDiagnostic");
			dump.setDaemon(true); dump.start();
			}
			demands = List.of();
			ownerGenerations.clear();
			int cancelled = coordinator.shutdown();
			contextFingerprints.clear();
			clearResultState("server_stopping");
			if (cancelled > 0) {
				WorldgenAssist.LOGGER.info("[CAWG] server.shutdown cancelled_jobs={}", cancelled);
			}
	}

	public void onServerStopped() { server = null; }

	public void onStartServerTick(MinecraftServer currentServer) { refreshDemands(currentServer); dispatchGenerationCandidates(currentServer); }

	private void requestPrefetchDispatch() {
		MinecraftServer current = server;
		if (current == null || !config.remoteExecutionEnabled() || !pipelineOptions.prefetch()
			|| (generationCandidates.size() == 0 && queuedTerrain.size() == 0)) return;
		long generation = cacheGeneration.get();
		PrefetchDispatch previous = prefetchDispatchQueued.get();
		if (previous != null && previous.server() == current && previous.generation() == generation) return;
		PrefetchDispatch dispatch = new PrefetchDispatch(current, generation);
		if (!prefetchDispatchQueued.compareAndSet(previous, dispatch)) return;
		try {
			current.schedule(current.wrapRunnable(() -> {
				if (!prefetchDispatchQueued.compareAndSet(dispatch, null)) return;
				if (server != current || generation != cacheGeneration.get()) return;
				refreshDemands(current);
				dispatchGenerationCandidates(current);
			}));
		} catch (RejectedExecutionException error) { prefetchDispatchQueued.compareAndSet(dispatch, null); }
	}

	public void onEndServerTick(MinecraftServer currentServer) {
			refreshDemands(currentServer);
			int expired = coordinator.expireTimedOut();
			// Connection replacement and physical owner-state cleanup both run on
			// the server thread. Cache access independently rejects quarantine as
			// soon as the watchdog marks it, including between server ticks.
			for (UUID ownerId : ownerGenerations.keySet()) {
				if (coordinator.isQuarantined(ownerId)) { invalidateOwner(ownerId); }
			}
			if (expired > 0) {
				logTimeout(expired, "server_tick");
			}
			if (diagnostics && ++diagnosticTicks % 200L == 0L) {
				WorldgenAssist.LOGGER.info("[CAWG] scheduler.summary ticks=200 no_owner={} ineligible={} no_capacity={} submitted={} pending={} decoder_queue={} validation_queue={} pipeline_pending={} candidates={} prefetch_sent={} prefetch_used={} demand_wait_expired={} late_results_discarded={} load_hints={} task_hints={} dependency_hints={} capacity_skips={}",
					noOwnerFallbacks.sumThenReset(), ineligibleFallbacks.sumThenReset(),
					noCapacityFallbacks.sumThenReset(), submittedJobs.sumThenReset(),
					coordinator.pendingCount(), resultDecoder.getQueue().size(), validationExecutor.getQueue().size(),
					preparation.size(), generationCandidates.size(), prefetchSent.sumThenReset(),
						prefetchUsed.sumThenReset(), demandWaitExpired.sumThenReset(), lateResultsDiscarded.sumThenReset(),loadHints.sumThenReset(),taskHints.sumThenReset(),dependencyHints.sumThenReset(),capacityLimitedRefills.sumThenReset());
			}
			if (config.predictionEnabled() && ++predictionTicks % config.predictionIntervalTicks() == 0L) {
				for (PlayerChunkDemand demand : demands) { predictForWorker(currentServer, demand.ownerId()); }
			}
	}

	private void refreshDemands(MinecraftServer currentServer) {
		// Publish immutable owner positions before level/chunk ticks as well as
		// after them, so fresh movement does not spend a tick using the old view.
		demands = coordinator.workerOwners().stream().map(owner -> currentServer.getPlayerList().getPlayer(owner))
			.filter(player -> player != null && !player.isChangingDimension() && !player.isSpectator()).map(player -> new PlayerChunkDemand(
				player.getUUID(), player.level().dimension().identifier(), player.chunkPosition().x(), player.chunkPosition().z(),
				Math.clamp(player.requestedViewDistance(), 2, currentServer.getPlayerList().getViewDistance()))).toList();
		for (var entry : predictedJobs.entrySet()) {
			var key = entry.getKey();
			if (!currentCacheKey(key) || demands.stream().noneMatch(d -> d.ownerId().equals(key.ownerId())
				&& d.dimension().equals(key.dimension()) && (entry.getValue().source().equals("prediction")
					? Math.max(Math.abs((long)d.chunkX() - key.chunkX()), Math.abs((long)d.chunkZ() - key.chunkZ()))
						<= d.viewDistance() + config.predictionLeadChunks() + 8
					: d.includesGeneration(key.dimension(), key.chunkX(), key.chunkZ())))) cancelAhead(key, entry.getValue());
		}
		resultCache.removeMatching(key -> !currentCacheKey(key) || demands.stream().noneMatch(d ->
			d.ownerId().equals(key.ownerId()) && d.dimension().equals(key.dimension())
			&& Math.max(Math.abs((long)d.chunkX() - key.chunkX()), Math.abs((long)d.chunkZ() - key.chunkZ()))
				<= d.viewDistance() + config.predictionLeadChunks() + 8));
	}

	private void dispatchGenerationCandidates(MinecraftServer currentServer) {
		if (!config.remoteExecutionEnabled() || !pipelineOptions.prefetch() || config.cacheEntries() == 0) return;
		if (generationCandidates.size() == 0 && queuedTerrain.size() == 0) return;
		// Avoid sorting hundreds of hints and creating an empty request batch while
		// every owner/global/preparation slot is occupied. This grants no lease;
		// callbacks and the next tick retry, and submission rechecks all bounds.
		boolean ownerAvailable = false;
		if (coordinator.pendingCount() < config.maxInFlightJobs() && preparation.size() < config.maxInFlightJobs()) {
			for (PlayerChunkDemand demand : demands) if (coordinator.ownerHasCapacity(demand.ownerId())) {
				ownerAvailable = true; break;
			}
		}
		if (!ownerAvailable) { capacityLimitedRefills.increment(); return; }
		// A little queued work keeps remote workers supplied; existing reservations
		// still enforce global/per-owner capacity. Stop before server queues fill.
		int refillWatermark = RemotePipelineOptions.refillWatermark(config.maxInFlightJobs(), ownerWindow);
		if (resultDecoder.getQueue().size() >= refillWatermark || validationExecutor.getQueue().size() >= refillWatermark) return;
		coordinator.withJobBatch(() -> {
			dispatchQueuedTerrain(refillWatermark);
			dispatchCandidateBatch(currentServer, refillWatermark);
		});
	}

	private void dispatchQueuedTerrain(int refillWatermark) {
		Set<UUID> fullOwners = new java.util.HashSet<>();
		for (var entry : queuedTerrain.ordered(System.nanoTime())) {
			if (validationExecutor.getQueue().size() >= refillWatermark) break;
			if (!queuedTerrain.contains(entry)) continue;
			var key = entry.key();
			// The snapshot came from actual eligibility, but movement, disconnect,
			// reload and a local start may invalidate it before this server task.
			if (!currentCacheKey(key) || startedTerrain.contains(key, System.nanoTime())
				|| !PlayerChunkDemand.selectGeneration(demands, key.dimension(), key.chunkX(), key.chunkZ())
					.map(d -> d.ownerId().equals(key.ownerId())).orElse(false)) {
				queuedTerrain.remove(entry); continue;
			}
			if (fullOwners.contains(entry.owner())) continue;
			if (submitPreparedAhead(entry.context(), key, "prefetch", false, "terrain_stage", entry.observedNanos())) {
				queuedTerrain.remove(entry);
				generationCandidates.remove(new GenerationPrefetchQueue.Position(key.dimension(), key.chunkX(), key.chunkZ()));
			} else fullOwners.add(entry.owner());
		}
	}

	private void dispatchCandidateBatch(MinecraftServer currentServer, int refillWatermark) {
		Set<UUID> fullOwners = new java.util.HashSet<>();
		for (var candidate : generationCandidates.ordered(demands, System.nanoTime(), prefetchLookahead)) {
			if (validationExecutor.getQueue().size() >= refillWatermark) break;
			if (fullOwners.contains(candidate.owner()) || !generationCandidates.contains(candidate)) continue;
			var pos = candidate.position();
			if (candidate.generation() != cacheGeneration.get() || candidate.ownerGeneration() !=
				ownerGenerations.getOrDefault(candidate.owner(), -2L)) { generationCandidates.remove(pos); continue; }
			ServerLevel level = currentServer.getLevel(ResourceKey.create(Registries.DIMENSION, pos.dimension()));
			if (level == null || !level.getWorldBorder().isWithinBounds(new ChunkPos(pos.x(), pos.z()))) {
				generationCandidates.remove(pos); continue;
			}
			if (submitAhead(level, candidate.owner(), new ChunkPos(pos.x(), pos.z()), "prefetch", false,candidate)) {
				if (!generationCandidates.contains(candidate)) {
					for (var entry : predictedJobs.entrySet()) if (entry.getKey().dimension().equals(pos.dimension())
						&& entry.getKey().chunkX() == pos.x() && entry.getKey().chunkZ() == pos.z()) cancelAhead(entry.getKey(), entry.getValue());
				}
				generationCandidates.remove(pos);
			} else fullOwners.add(candidate.owner());
		}
	}

	private boolean submitAhead(ServerLevel level, UUID owner, ChunkPos pos, String source, boolean reserveForDemand) {
		return submitAhead(level,owner,pos,source,reserveForDemand,null);
	}
	private boolean submitAhead(ServerLevel level, UUID owner, ChunkPos pos, String source, boolean reserveForDemand,
		GenerationPrefetchQueue.Candidate candidate) {
		if (((ReadyTerrainLookup)level.getChunkSource()).worldgenAssist$terrainReady(pos.x(),pos.z())) return true;
		var eligible = RemoteWorldgenEligibility.evaluatePrediction(level);
		if (eligible.isEmpty()) return false;
		var context = eligible.get();
		var fingerprint = contextFingerprints.get(level.dimension());
		if (fingerprint == null) {
			long generation = cacheGeneration.get();
			if (fingerprintPreparation.add(level.dimension())) {
				try {
					validationExecutor.execute(() -> {
						try {
							var prepared = WorldgenContextFingerprintFactory.create(level, context.generator());
							synchronized (resultStateLock) {
								if (generation == cacheGeneration.get()) contextFingerprints.putIfAbsent(level.dimension(), prepared);
							}
						} finally { fingerprintPreparation.remove(level.dimension()); requestPrefetchDispatch(); }
					});
				} catch (RejectedExecutionException error) { fingerprintPreparation.remove(level.dimension()); }
			}
			return false;
		}
		var settings = context.settings().unwrapKey().orElseThrow().identifier();
		var key = createCacheKey(owner, level, pos, fingerprint, settings, context.noise(),
			io.github.genichimaruo.worldgenassist.common.SurfaceDensityData.selectedKind(settings, context.settings().value()));
		return submitPreparedAhead(context, key, source, reserveForDemand,
			candidate == null ? "prediction" : candidate.dependencyTask() ? "task_dependency" : candidate.earlyTask() ? "task" : "loaded",
			candidate == null ? 0L : candidate.observedNanos());
	}

	/** Called only by the existing server dispatcher, with a captured eligible
	 * graph or a server-evaluated prediction. No live chunk lookup for actual work. */
	private boolean submitPreparedAhead(RemoteWorldgenEligibility.SpeculativeContext context,
		RemoteDensityResultCache.Key key, String source, boolean reserveForDemand, String hint, long observedNanos) {
		var level = context.level();
		var owner = key.ownerId();
		var pos = new ChunkPos(key.chunkX(), key.chunkZ());
		var fingerprint = key.contextFingerprint();
		var settings = key.noiseSettings();
		if (!currentCacheKey(key)) return false;
		if (startedTerrain.contains(key,System.nanoTime())) return true;
		if (hasCachedResult(key) || predictedJobs.containsKey(key)) return true;
		if (!hasCompleteAuditCapacity(key)) return false;
		if (resultCache.size() + predictedJobs.size() >= config.cacheEntries() || !dispatching.add(key)) return false;
		var ticket = preparation.reserve(owner, Math.max(1, coordinator.ownerJobLimit(owner)));
		if (ticket == null) { dispatching.remove(key); return false; }
		try {
			long started = System.nanoTime();
			var submission = reserveForDemand
				? coordinator.trySubmitPredictionForOwner(owner, key.dimension(), pos.x(), pos.z(), fingerprint,
					identity -> createPredictionJob(identity, context, settings))
				: coordinator.trySubmitForOwner(owner, key.dimension(), pos.x(), pos.z(), fingerprint,
					identity -> createPredictionJob(identity, context, settings));
			if (submission.isEmpty()) { ticket.cancel(); return false; }
			var remote = submission.get();
			var validated = prepareValidation(remote, key, level, context.settings().value(), context.noise(), ticket, source, started);
			var abandoned = new java.util.concurrent.atomic.AtomicBoolean();
			var completed = validated.thenApply(result -> completePrediction(owner, remote.job(), result, key, started, abandoned, source));
			var attempt = new PredictionAttempt(owner, remote.job(), completed, source, validated, abandoned, started);
			predictedJobs.put(key, attempt);
			completed.whenComplete((value, error) -> {
				predictedJobs.remove(key, attempt);
				if (error != null) { validated.cancel(false); transferMetrics.remove(remote.job().identity().jobId()); }
			});
			if (!currentCacheKey(key)) { cancelAhead(key, attempt); return false; }
			if (startedTerrain.contains(key,System.nanoTime())) { cancelAhead(key,attempt); return true; }
			if (source.equals("prefetch")) prefetchSent.increment();
			trace("[CAWG] {}.sent id={} owner={} chunk={},{} samples={}",
				source, remote.job().identity().jobId(), owner, pos.x(), pos.z(), remote.job().sampleCount());
			WorldgenAssist.LOGGER.info("[CAWG] job.sent id={} chunk={},{} samples={} timeout_ms={} owner={} source={} hint={} candidate_age_ms={}",
				remote.job().identity().jobId(), pos.x(), pos.z(), remote.job().sampleCount(), config.jobTimeout().toMillis(), owner, source,
				hint, observedNanos == 0L ? 0.0 : (started-observedNanos)/1_000_000.0);
			return true;
		} catch (RuntimeException error) { ticket.cancel(); return false; }
		finally { dispatching.remove(key); }
	}

	private void cancelAhead(RemoteDensityResultCache.Key key, PredictionAttempt attempt) {
		boolean removed;
		synchronized (resultStateLock) {
			attempt.abandoned().set(true);
			removed = predictedJobs.remove(key, attempt);
			resultCache.remove(key);
		}
		if (removed) {
			attempt.result().cancel(false);
			attempt.validation().cancel(false);
			coordinator.cancelJob(attempt.ownerId(), attempt.job().identity());
			resultCache.remove(key);
		}
	}

	private void discardAhead(net.minecraft.resources.Identifier dimension, ChunkPos pos) {
		discardAhead(dimension,pos,-1L);
	}
	private void discardAhead(net.minecraft.resources.Identifier dimension, ChunkPos pos, long expectedGeneration) {
		java.util.List<java.util.Map.Entry<RemoteDensityResultCache.Key,PredictionAttempt>> discarded=new java.util.ArrayList<>();
		synchronized(resultStateLock) {
			if(expectedGeneration>=0&&expectedGeneration!=cacheGeneration.get())return;
			generationCandidates.remove(new GenerationPrefetchQueue.Position(dimension,pos.x(),pos.z()));
			for (var entry:predictedJobs.entrySet()) {
				var key=entry.getKey();
				if (key.dimension().equals(dimension)&&key.chunkX()==pos.x()&&key.chunkZ()==pos.z()) {
					entry.getValue().abandoned().set(true);
					if(predictedJobs.remove(key,entry.getValue()))discarded.add(entry);
				}
			}
			resultCache.removeMatching(key -> key.dimension().equals(dimension) && key.chunkX() == pos.x() && key.chunkZ() == pos.z());
		}
		for(var entry:discarded) {
			var attempt=entry.getValue();attempt.result().cancel(false);attempt.validation().cancel(false);
			coordinator.cancelJob(attempt.ownerId(),attempt.job().identity());
		}
	}

	private CompletableFuture<TerrainDensityResult> prepareValidation(RemoteJobCoordinator.Submission remote,
		RemoteDensityResultCache.Key key, ServerLevel level, net.minecraft.world.level.levelgen.NoiseGeneratorSettings settings,
		NoiseSettings noise, BoundedRemotePreparation<RemoteDensityValidator.Prepared, TerrainDensityResult>.Ticket ticket,
		String source, long started) {
		long queued = System.nanoTime();
		var randomState = level.getChunkSource().randomState();
		boolean completeTerrain = remote.job().workKind() == io.github.genichimaruo.worldgenassist.common.TerrainWorkKind.COMPLETE_TERRAIN;
		CompleteTerrainAuditPolicy.Token<CompleteAuditContext> audit;
		try {
			audit = completeTerrain ? completeAudits.select(new CompleteAuditContext(remote.ownerId(), key.ownerGeneration(),
				key.generation(), key.dimension(), key.contextFingerprint()), remote.job().identity().jobId()) : null;
		} catch (RuntimeException error) {
			ticket.cancel(); coordinator.cancelJob(remote.ownerId(), remote.job().identity());
			return CompletableFuture.failedFuture(error);
		}
		java.util.function.Supplier<RemoteDensityValidator.Prepared> prepare = () -> {
			long prepareStarted = System.nanoTime();
			RemoteDensityValidator.Prepared prepared;
			if (completeTerrain) {
				if (!completeAudits.current(audit) || !currentCacheKey(key)) throw new java.util.concurrent.CancellationException("Obsolete full audit");
				io.github.genichimaruo.worldgenassist.common.CompleteTerrainData expected = null;
				if (audit.requiresFullAudit()) {
					var generator = RemoteWorldgenEligibility.noiseDelegate(level.getChunkSource().getGenerator());
					expected = completeAuditComputer(key, audit, level).compute(remote.job(), generator, randomState);
				}
				prepared = RemoteDensityValidator.Prepared.completeTerrain(remote.job(), expected, System.nanoTime() - prepareStarted);
			} else prepared = RemoteDensityValidator.prepare(remote.job(), pipelineOptions.prepareValidation()
				? config.validationSampleCells() : 0, randomState, settings, noise, validationRandom);
			trace("[CAWG] job.validation_ready id={} source={} prepare_queue_ms={} prepare_ms={}",
				remote.job().identity().jobId(), source, (prepareStarted - queued) / 1_000_000.0,
				prepared.prepareNanos() / 1_000_000.0);
			return prepared;
		};
		java.util.function.BiFunction<TerrainDensityResult, RemoteDensityValidator.Prepared, TerrainDensityResult> compare = (density, prepared) -> {
			if (!currentCacheKey(key)) throw new java.util.concurrent.CancellationException("Obsolete validation context");
			long compareStarted = System.nanoTime();
			try {
				var metrics = completeTerrain || pipelineOptions.prepareValidation() ? prepared.compare(density)
					: RemoteDensityValidator.validate(remote.job(), density, config.validationSampleCells(),
						randomState, settings, noise, validationRandom);
				WorldgenAssist.LOGGER.info("[CAWG] job.validation_complete id={} source={} sampled_cells={} sampled_values={} validation_ms={} compare_ms={}",
					remote.job().identity().jobId(), source, metrics.sampledCells(), metrics.sampledValues(),
						metrics.elapsedNanos() / 1_000_000.0, (System.nanoTime() - compareStarted) / 1_000_000.0);
				synchronized (resultStateLock) {
					if (completeTerrain) {
						if (!currentCacheKey(key) || !completeAudits.accept(audit)) throw new java.util.concurrent.CancellationException("Full audit authority expired");
						WorldgenAssist.LOGGER.info("[CAWG] job.full_terrain_accepted id={} audited={} blocks={}",
							remote.job().identity().jobId(), audit.requiresFullAudit(), density.densityCount());
					}
					if (currentCacheKey(key)) demandWait.recordReady(remote.ownerId(), System.nanoTime() - started);
				}
				return density;
			} catch (RemoteDensityValidator.RemoteDensityValidationException error) {
				quarantineWorker(remote.ownerId(), remote.job().identity().jobId(), source, "density_mismatch");
				throw error;
			}
		};
		CompletableFuture<TerrainDensityResult> result;
		try {
			// Unaudited complete jobs have no CPU preparation. Do not queue their
			// constant shape/identity validator behind independently computed audits.
			// The same admitted ticket, comparison and final authority checks remain.
			result = completeTerrain && !audit.requiresFullAudit()
				? ticket.startPrepared(prepare.get(), remote.result(), compare)
				: ticket.start(prepare, remote.result(), compare);
		} catch (RuntimeException error) {
			if (audit != null) completeAudits.cancel(audit);
			ticket.cancel(); coordinator.cancelJob(remote.ownerId(), remote.job().identity());
			return CompletableFuture.failedFuture(error);
		}
		long remaining = Math.max(1L, config.jobTimeout().toNanos() - (System.nanoTime() - started));
		result.orTimeout(remaining, TimeUnit.NANOSECONDS).whenComplete((density, error) -> {
			if (audit != null) completeAudits.cancel(audit);
			if (error != null) {
				ticket.cancel();
				coordinator.cancelJob(remote.ownerId(), remote.job().identity());
				transferMetrics.remove(remote.job().identity().jobId());
			}
			requestPrefetchDispatch();
		});
		return result;
	}
	private io.github.genichimaruo.worldgenassist.common.PrivateTerrainComputer completeAuditComputer(
		RemoteDensityResultCache.Key key, CompleteTerrainAuditPolicy.Token<CompleteAuditContext> audit, ServerLevel level) {
		CompleteAuditContext context = new CompleteAuditContext(key.ownerId(), key.ownerGeneration(),
			key.generation(), key.dimension(), key.contextFingerprint());
		synchronized (resultStateLock) {
			if (!currentCacheKey(key) || !completeAudits.current(audit)) throw new java.util.concurrent.CancellationException("Obsolete audit context");
			var existing = completeAuditComputers.get(context);
			if (existing != null) return existing;
		}
		// Registry/codec construction stays outside the shared authority lock.
		var created = new io.github.genichimaruo.worldgenassist.common.PrivateTerrainComputer(level.registryAccess());
		synchronized (resultStateLock) {
			if (!currentCacheKey(key) || !completeAudits.current(audit)) throw new java.util.concurrent.CancellationException("Obsolete audit context");
			var existing = completeAuditComputers.get(context);
			if (existing != null) return existing;
			if (completeAuditComputers.size() >= 64) throw new java.util.concurrent.CancellationException("Audit computer limit");
			completeAuditComputers.put(context, created);
			return created;
		}
	}

	private CompletableFuture<TerrainDensityResult> awaitDemand(CompletableFuture<TerrainDensityResult> prepared,
		UUID owner, TerrainDensityJob job, RemoteDensityResultCache.Key key, long requestStarted) {
		boolean baseline = !pipelineOptions.prefetch() && !pipelineOptions.prepareValidation();
		long base = baseline ? config.jobTimeout().toMillis() : pipelineOptions.demandWaitMillis();
		boolean adaptive = !baseline && pipelineOptions.adaptiveDemandWait();
		trace("[CAWG] demand.wait id={} adaptive={} budget_ms={} maximum_ms={}",
			job.identity().jobId(), adaptive, demandWait.initialMillis(owner, requestStarted, base, adaptive),
			adaptive ? AdaptiveDemandWait.maximumMillis(base) : base);
		return demandWait.await(owner, requestStarted, base, adaptive, prepared,
			() -> transferMetrics.containsKey(job.identity().jobId()), () -> {
				demandWaitExpired.increment();
				prepared.cancel(false);
				coordinator.cancelJob(owner, job.identity());
				resultCache.remove(key);
			});
	}

	private void clearResultState(String reason) {
		preparation.cancelAll();
		generationCandidates.clear();
		long generation;
		int cached;
		int metrics;
		int predictions;
		synchronized (resultStateLock) {
			generation = cacheGeneration.incrementAndGet();
			completeAudits.clear();
			completeAuditComputers.clear();
			prefetchDispatchQueued.set(null);
			demandWait.clear();
			cached = resultCache.clear();
			startedTerrain.clear();
			queuedTerrain.clear();
			metrics = transferMetrics.size();
			predictions = predictedJobs.size();
			transferMetrics.clear();
			predictedJobs.clear();
			predictor.clear();
			predictionTicks = 0L;
		}
		if (cached > 0 || metrics > 0 || predictions > 0) {
			WorldgenAssist.LOGGER.info(
				"[CAWG] cache.invalidated reason={} generation={} entries={} pending_metrics={} predictions={}",
				reason,
				generation,
				cached,
				metrics,
				predictions
			);
		}
	}

	private void decodeResult(UUID ownerId, TerrainDensityResultEnvelope envelope, long receivedNanos,
		java.util.function.BooleanSupplier currentConnection) {
		long startedNanos = System.nanoTime();
		try {
			if (!currentConnection.getAsBoolean()) throw new java.util.concurrent.CancellationException("Obsolete connection");
			TerrainDensityResult result = envelope.decode();
			if (!currentConnection.getAsBoolean()) throw new java.util.concurrent.CancellationException("Obsolete connection");
			long decodeNanos = System.nanoTime() - startedNanos;
			transferMetrics.put(
				envelope.identity().jobId(),
				new ResultTransferMetrics(
					envelope.encoding(),
					envelope.rawDensityBytes(),
					envelope.encodedDensityBytes(),
					envelope.clientEncodeNanos(),
					decodeNanos, receivedNanos
				)
			);
			PendingTerrainJobRegistry.ResponseStatus status = coordinator.completeClaimedResult(ownerId, result);
			if (status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED) {
				trace(
						"[CAWG] job.result_decoded id={} encoding={} raw_bytes={} encoded_bytes={} decode_ms={} decode_queue_ms={}",
					envelope.identity().jobId(),
					envelope.encoding(),
					envelope.rawDensityBytes(),
					envelope.encodedDensityBytes(),
						decodeNanos / 1_000_000.0, (startedNanos - receivedNanos) / 1_000_000.0
				);
				requestPrefetchDispatch();
			} else {
				transferMetrics.remove(envelope.identity().jobId());
				WorldgenAssist.LOGGER.warn(
					"[CAWG] job.result_rejected id={} owner={} status={}",
					envelope.identity().jobId(),
					ownerId,
					status
				);
			}
		} catch (java.util.concurrent.CancellationException error) {
			transferMetrics.remove(envelope.identity().jobId());
			coordinator.failClaimedResult(ownerId, envelope.identity(), error);
		} catch (RuntimeException | Error error) {
			transferMetrics.remove(envelope.identity().jobId());
			PendingTerrainJobRegistry.ResponseStatus status = coordinator.failClaimedResult(
				ownerId,
				envelope.identity(),
				error
			);
			WorldgenAssist.LOGGER.warn(
				"[CAWG] job.decode_failed id={} owner={} status={} encoding={} encoded_bytes={}",
				envelope.identity().jobId(),
				ownerId,
				status,
				envelope.encoding(),
				envelope.encodedDensityBytes(),
				error
			);
			if (status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED) {
				quarantineWorker(ownerId, envelope.identity().jobId(), "decode", "malformed_result");
			}
		}
	}

	private void predictForWorker(MinecraftServer currentServer, UUID owner) {
		ServerPlayer player = currentServer.getPlayerList().getPlayer(owner);
		if (player == null || player.isChangingDimension()) {
			predictor.remove(owner);
			return;
		}
		int effectiveViewDistance = PlayerOwnedChunkPredictor.effectiveViewDistance(
			player.requestedViewDistance(),
			currentServer.getPlayerList().getViewDistance()
		);
		Optional<PlayerOwnedChunkPredictor.Prediction> prediction = predictor.observe(
			owner,
			player.level().dimension().identifier(),
			player.chunkPosition().x(),
			player.chunkPosition().z(),
			effectiveViewDistance,
			config.predictionLeadChunks()
		);
		prediction.ifPresent(candidate -> submitPrediction(player.level(), candidate));
	}

	private void submitPrediction(ServerLevel level, PlayerOwnedChunkPredictor.Prediction prediction) {
		PlayerOwnedChunkPredictor.Prediction selected = null;
		for (int additional = 0; additional <= PlayerOwnedChunkPredictor.MAX_UNLOADED_SCAN_CHUNKS; additional++) {
			Optional<PlayerOwnedChunkPredictor.Prediction> advanced = PlayerOwnedChunkPredictor.advance(prediction, additional);
			if (advanced.isEmpty()) {
				break;
			}
			PlayerOwnedChunkPredictor.Prediction candidate = advanced.get();
			ChunkPos candidatePos = new ChunkPos(candidate.chunkX(), candidate.chunkZ());
			if (!level.getWorldBorder().isWithinBounds(candidatePos)) {
				break;
			}
			if (!level.getChunkSource().hasChunk(candidatePos.x(), candidatePos.z())) {
				selected = candidate;
				break;
			}
		}
		if (selected == null) {
			return;
		}
		submitAhead(level, selected.ownerId(), new ChunkPos(selected.chunkX(), selected.chunkZ()), "prediction", true);
	}

	private TerrainDensityResult completePrediction(
		UUID ownerId,
		TerrainDensityJob job,
		TerrainDensityResult result,
		RemoteDensityResultCache.Key cacheKey,
		long startedNanos, java.util.concurrent.atomic.AtomicBoolean abandoned, String source
	) {
		synchronized (resultStateLock) {
			if (abandoned.get() || !storeCachedResult(cacheKey, result, source)) {
				throw new java.util.concurrent.CancellationException("Prediction context was invalidated before completion");
			}
		}
		coordinator.recordValidated(ownerId);
		ResultTransferMetrics metrics = transferMetrics.remove(job.identity().jobId());
		logResultReceived(job, result, metrics, startedNanos, source);
		trace(
			"[CAWG] prediction.complete id={} chunk={},{} total_ms={}",
			job.identity().jobId(),
			job.identity().chunkX(),
			job.identity().chunkZ(),
			(System.nanoTime() - startedNanos) / 1_000_000.0
		);
		return result;
	}

	private void startTimeoutWatchdog() {
		if (!config.remoteExecutionEnabled()) {
			return;
		}
		long intervalMillis = Math.clamp(config.jobTimeout().toMillis() / 4L, 10L, 100L);
		timeoutWatchdog.scheduleWithFixedDelay(() -> {
			try {
				int expired = coordinator.expireTimedOut(false);
				if (expired > 0) {
					logTimeout(expired, "watchdog");
				}
			} catch (RuntimeException error) {
				WorldgenAssist.LOGGER.error("[CAWG] timeout.watchdog_failed", error);
			}
		}, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
	}

	private void logTimeout(int expired, String source) {
		WorldgenAssist.LOGGER.info(
			"[CAWG] job.timeout count={} timeout_ms={} source={}",
			expired,
			config.jobTimeout().toMillis(),
			source
		);
	}

	private CompletableFuture<ChunkAccess> generate(
		WorldGenContext context,
		ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk,
		Supplier<CompletableFuture<ChunkAccess>> localFallback
	) {
		generationCandidates.remove(new GenerationPrefetchQueue.Position(context.level().dimension().identifier(),
			chunk.getPos().x(), chunk.getPos().z()));
		if (!config.remoteExecutionEnabled()) {
			return invokeFallback(localFallback);
		}
		PlayerChunkDemand demand = PlayerChunkDemand.selectGeneration(demands, context.level().dimension().identifier(),
			chunk.getPos().x(), chunk.getPos().z()).orElse(null);
		if (demand == null) {
			noOwnerFallbacks.increment(); discardAhead(context.level().dimension().identifier(), chunk.getPos());
			return invokeFallback(localFallback);
		}
		Optional<RemoteWorldgenEligibility.EligibleContext> eligible = RemoteWorldgenEligibility.evaluate(context, step, chunks, chunk);
		if (eligible.isEmpty()) {
			ineligibleFallbacks.increment();
			discardAhead(context.level().dimension().identifier(), chunk.getPos());
			return invokeFallback(localFallback);
		}
		RemoteWorldgenEligibility.EligibleContext eligibleContext = eligible.get();
		Optional<RemoteJobCoordinator.Submission> submission;
		RemoteDensityResultCache.Key cacheKey;
		RemoteDensityResultCache.Key dispatchKey = null;
		long startedNanos = System.nanoTime();
		BoundedRemotePreparation<RemoteDensityValidator.Prepared, TerrainDensityResult>.Ticket ticket = null;
		try {
			WorldgenContextFingerprint fingerprint = contextFingerprints.computeIfAbsent(
				eligibleContext.level().dimension(),
				ignored -> WorldgenContextFingerprintFactory.create(eligibleContext.level(), eligibleContext.generator())
			);
			var noiseSettings = eligibleContext.settings().unwrapKey().orElseThrow().identifier();
			cacheKey = createCacheKey(
				demand.ownerId(),
				eligibleContext.level(),
				chunk.getPos(),
				fingerprint,
				noiseSettings,
				eligibleContext.noise(),
				io.github.genichimaruo.worldgenassist.common.SurfaceDensityData.selectedKind(noiseSettings, eligibleContext.settings().value())
			);
			AheadAvailability ahead = availableAhead(cacheKey);
			Optional<TerrainDensityResult> cached = ahead.cached();
			if (cached.isPresent()) {
				prefetchUsed.increment();
				TerrainDensityResult result = cached.orElseThrow();
				TerrainJobIdentity identity = result.identity();
				TerrainDensityJob job = createJob(identity, eligibleContext, noiseSettings);
				WorldgenAssist.LOGGER.info(
					"[CAWG] cache.hit id={} chunk={},{} samples={} entries={}",
					identity.jobId(),
					identity.chunkX(),
					identity.chunkZ(),
					result.densityCount(),
					resultCache.size()
				);
				return applyRemoteDensity(chunk, job, result, cacheKey, null, eligibleContext, localFallback, startedNanos, "cache");
			}
			PredictionAttempt prediction = ahead.attempt();
			if (cacheKey.workKind() != io.github.genichimaruo.worldgenassist.common.TerrainWorkKind.DENSITY
				&& pipelineOptions.readySurfaceOnly() && pipelineOptions.prefetch()) {
				return generateWithReadySurface(chunk, cacheKey, demand, eligibleContext, localFallback, startedNanos);
			}
			if (prediction != null) {
				trace(
					"[CAWG] prediction.join id={} owner={} chunk={},{}",
					prediction.job().identity().jobId(),
					prediction.ownerId(),
					cacheKey.chunkX(),
					cacheKey.chunkZ()
				);
				return awaitDemand(prediction.result(), prediction.ownerId(), prediction.job(), cacheKey, prediction.startedNanos()).handle((result, error) -> {
					if (error != null) {
						cancelAhead(cacheKey, prediction);
						return invokeFallback(localFallback);
					}
					resultCache.remove(cacheKey);
					prefetchUsed.increment();
					return applyRemoteDensity(
						chunk,
						prediction.job(),
						result,
						cacheKey,
						prediction.ownerId(),
						eligibleContext,
						localFallback,
						startedNanos,
						prediction.source()
					);
				}).thenCompose(future -> future);
			}
			if (pipelineOptions.adaptiveDemandWait() && (pipelineOptions.prefetch() || pipelineOptions.prepareValidation())
				&& !demandWait.shouldSubmitDirect(demand.ownerId(), pipelineOptions.demandWaitMillis(), hasAheadWork(demand.ownerId()))) {
				trace("[CAWG] demand.local chunk={},{} reason=latency_budget owner={}",
					chunk.getPos().x(), chunk.getPos().z(), demand.ownerId());
				return invokeFallback(localFallback);
			}
			if (!dispatching.add(cacheKey)) return invokeFallback(localFallback);
			dispatchKey = cacheKey;
			ticket = preparation.reserve(demand.ownerId(), Math.max(1, coordinator.ownerJobLimit(demand.ownerId())));
			if (ticket == null) { dispatching.remove(cacheKey); noCapacityFallbacks.increment(); return invokeFallback(localFallback); }
			submission = coordinator.trySubmitForOwner(
				demand.ownerId(),
				eligibleContext.level().dimension().identifier(),
				chunk.getPos().x(),
				chunk.getPos().z(),
				fingerprint,
				identity -> createJob(identity, eligibleContext, noiseSettings)
			);
			dispatching.remove(cacheKey);
		} catch (RuntimeException error) {
			if (ticket != null) ticket.cancel();
			if (dispatchKey != null) dispatching.remove(dispatchKey);
			WorldgenAssist.LOGGER.warn(
				"[CAWG] job.prepare_rejected chunk={},{} reason={}",
				chunk.getPos().x(),
				chunk.getPos().z(),
				error.getClass().getSimpleName(),
				error
			);
			return invokeFallback(localFallback);
		}
		if (submission.isEmpty()) {
			if (ticket != null) ticket.cancel();
			noCapacityFallbacks.increment();
			return invokeFallback(localFallback);
		}

		RemoteJobCoordinator.Submission remote = submission.get();
		submittedJobs.increment();
		WorldgenAssist.LOGGER.info(
			"[CAWG] cache.miss chunk={},{} entries={} capacity={}",
			cacheKey.chunkX(),
			cacheKey.chunkZ(),
			resultCache.size(),
			config.cacheEntries()
		);
		WorldgenAssist.LOGGER.info(
			"[CAWG] job.sent id={} chunk={},{} samples={} timeout_ms={} owner={}",
			remote.job().identity().jobId(),
			remote.job().identity().chunkX(),
			remote.job().identity().chunkZ(),
			remote.job().sampleCount(),
			config.jobTimeout().toMillis(),
			remote.ownerId()
		);
		var validated = prepareValidation(remote, cacheKey, eligibleContext.level(), eligibleContext.settings().value(),
			eligibleContext.noise(), ticket, "remote", startedNanos);
		RemoteDensityResultCache.Key applicationKey = cacheKey;
		return awaitDemand(validated, remote.ownerId(), remote.job(), applicationKey, startedNanos).handle((result, error) -> {
			if (error != null) {
				WorldgenAssist.LOGGER.info(
					"[CAWG] job.fallback_local id={} chunk={},{} reason={}",
					remote.job().identity().jobId(),
					remote.job().identity().chunkX(),
					remote.job().identity().chunkZ(),
					rootCause(error).getClass().getSimpleName()
				);
				return invokeFallback(localFallback);
			}
			return applyRemoteDensity(
				chunk,
				remote.job(),
				result,
				applicationKey,
				remote.ownerId(),
				eligibleContext,
				localFallback,
				startedNanos,
				"remote"
			);
		}).thenCompose(future -> future);
	}

	/** Let vanilla enter its executor immediately. Claim only output already
	 * validated when the queued terrain supplier actually begins. */
	private CompletableFuture<ChunkAccess> generateWithReadySurface(ChunkAccess chunk, RemoteDensityResultCache.Key key,
		PlayerChunkDemand demand, RemoteWorldgenEligibility.EligibleContext context,
		Supplier<CompletableFuture<ChunkAccess>> local, long started) {
		RemoteDensityTarget target = (RemoteDensityTarget)chunk;
		var settings = context.settings().value();
		var state = context.level().getChunkSource().randomState();
		long seed = context.level().getSeed();
		boolean structures = context.level().getServer().getWorldGenSettings().options().generateStructures();
		var opportunity = new RemoteDensityOpportunity(() -> {
			RemoteDensityField field=null;
			synchronized (resultStateLock) {
				if (!currentCacheKey(key)) return null;
				startedTerrain.mark(key,System.nanoTime());
				queuedTerrain.remove(key);
				generationCandidates.remove(new GenerationPrefetchQueue.Position(key.dimension(),key.chunkX(),key.chunkZ()));
				var cached = resultCache.takeResult(key);
				if (cached.isPresent()) {
				var result = cached.orElseThrow();
				var job = new TerrainDensityJob(result.identity(), seed, structures, key.noiseSettings(), key.minY(), key.height(),
					1, 1, key.workKind());
				field = new RemoteDensityField(job, result, settings, state);
				target.worldgenAssist$installRemoteDensity(field);
				prefetchUsed.increment();
				}
			}
			PredictionAttempt pending=predictedJobs.get(key);
			if(pending!=null)cancelAhead(key,pending);
			requestPrefetchDispatch();
			return field;
		}, (field, elapsed, error) -> {
			if (error == null) WorldgenAssist.LOGGER.info(
				"[CAWG] job.complete id={} source=cache apply_ms={} total_ms={} work_kind={} remote_samples={} grid_samples={} decision_samples={} application=queued_ready",
				field.jobId(), elapsed / 1_000_000.0, (System.nanoTime() - started) / 1_000_000.0,
				field.workKind(), field.remoteSamplesServed(), field.gridSamplesServed(),field.decisionSamplesServed());
		}, () -> resultCache.available(key) ? RemoteDensityOpportunity.Availability.READY
			: predictedJobs.containsKey(key) || dispatching.contains(key) || queuedTerrain.contains(key, System.nanoTime())
				? RemoteDensityOpportunity.Availability.PENDING
			: RemoteDensityOpportunity.Availability.LOCAL);
		target.worldgenAssist$installRemoteOpportunity(opportunity);
		synchronized (resultStateLock) {
			if (currentCacheKey(key) && !startedTerrain.contains(key, System.nanoTime())) {
				long now = System.nanoTime();
				queuedTerrain.offer(key, demand.ownerId(), new RemoteWorldgenEligibility.SpeculativeContext(
					context.level(), context.generator(), context.settings(), context.noise()), now, now + config.jobTimeout().toNanos());
			}
		}
		generationCandidates.offer(demand, chunk.getPos().x(), chunk.getPos().z(), key.generation(), key.ownerGeneration(),
			System.nanoTime() + config.jobTimeout().toNanos());
		requestPrefetchDispatch();
		return invokeFallback(local).whenComplete((result, error) -> {
			queuedTerrain.remove(key);
			target.worldgenAssist$clearRemoteOpportunity(opportunity);
		});
	}

	private static TerrainDensityJob createJob(
		TerrainJobIdentity identity,
		RemoteWorldgenEligibility.EligibleContext eligibleContext,
		net.minecraft.resources.Identifier noiseSettings
	) {
		return new TerrainDensityJob(
			identity,
			eligibleContext.level().getSeed(),
			eligibleContext.level().getServer().getWorldGenSettings().options().generateStructures(),
			noiseSettings,
			eligibleContext.noise().minY(),
			eligibleContext.noise().height(),
			1,
			1,
			io.github.genichimaruo.worldgenassist.common.SurfaceDensityData.selectedKind(noiseSettings, eligibleContext.settings().value())
		);
	}

	private static TerrainDensityJob createPredictionJob(
		TerrainJobIdentity identity,
		RemoteWorldgenEligibility.SpeculativeContext speculative,
		net.minecraft.resources.Identifier noiseSettings
	) {
		return new TerrainDensityJob(
			identity,
			speculative.level().getSeed(),
			speculative.level().getServer().getWorldGenSettings().options().generateStructures(),
			noiseSettings,
			speculative.noise().minY(),
			speculative.noise().height(),
			1,
			1,
			io.github.genichimaruo.worldgenassist.common.SurfaceDensityData.selectedKind(noiseSettings, speculative.settings().value())
		);
	}

	private RemoteDensityResultCache.Key createCacheKey(
		UUID ownerId,
		ServerLevel level,
		ChunkPos chunkPos,
		WorldgenContextFingerprint fingerprint,
		net.minecraft.resources.Identifier noiseSettings,
		NoiseSettings noise,
		io.github.genichimaruo.worldgenassist.common.TerrainWorkKind workKind
	) {
		return new RemoteDensityResultCache.Key(
			cacheGeneration.get(),
			level.dimension().identifier(),
			chunkPos.x(),
			chunkPos.z(),
			fingerprint,
			noiseSettings,
			noise.minY(),
			noise.height(),
			1,
			1,
			ownerId,
			ownerGenerations.getOrDefault(ownerId, -1L),
			workKind
		);
	}

	private boolean currentCacheKey(RemoteDensityResultCache.Key key) {
		return key.generation() == cacheGeneration.get() && key.ownerId() != null
			&& !coordinator.isQuarantined(key.ownerId())
			&& key.ownerGeneration() == ownerGenerations.getOrDefault(key.ownerId(), -2L);
	}

	private void invalidateOwner(UUID ownerId) {
		invalidateOwner(ownerId, false);
	}

	private void invalidateOwner(UUID ownerId, boolean retainConnection) {
		synchronized (resultStateLock) {
			Long previous = ownerGenerations.remove(ownerId);
			completeAudits.invalidateMatching(context -> ownerId.equals(context.owner()));
			completeAuditComputers.keySet().removeIf(context -> ownerId.equals(context.owner()));
			if (retainConnection && previous != null) {
				ownerGenerations.put(ownerId, nextOwnerGeneration.incrementAndGet());
			}
			resultCache.removeOwner(ownerId);
			queuedTerrain.removeOwner(ownerId);
			startedTerrain.removeMatching(key->ownerId.equals(key.ownerId()));
			demandWait.removeOwner(ownerId);
			predictedJobs.keySet().removeIf(key -> ownerId.equals(key.ownerId()));
			demands = demands.stream().filter(demand -> !ownerId.equals(demand.ownerId())).toList();
		}
		preparation.cancelOwner(ownerId);
		generationCandidates.removeOwner(ownerId);
	}

	private boolean hasAheadWork(UUID owner) {
		synchronized (resultStateLock) {
			return predictedJobs.entrySet().stream().anyMatch(entry -> owner.equals(entry.getKey().ownerId())
				&& currentCacheKey(entry.getKey()) && !entry.getValue().result().isDone());
		}
	}
	private record CompleteAuditContext(UUID owner, long ownerGeneration, long generation,
		net.minecraft.resources.Identifier dimension, WorldgenContextFingerprint fingerprint) { }
	private boolean hasCompleteAuditCapacity(RemoteDensityResultCache.Key key) {
		return key.workKind() != io.github.genichimaruo.worldgenassist.common.TerrainWorkKind.COMPLETE_TERRAIN
			|| completeAudits.canAdmit(new CompleteAuditContext(key.ownerId(), key.ownerGeneration(), key.generation(), key.dimension(), key.contextFingerprint()));
	}

	private AheadAvailability availableAhead(RemoteDensityResultCache.Key cacheKey) {
		synchronized (resultStateLock) {
			if (!currentCacheKey(cacheKey)) return new AheadAvailability(Optional.empty(), null);
			Optional<TerrainDensityResult> cached = resultCache.takeResult(cacheKey);
			return new AheadAvailability(cached, cached.isPresent() ? null : predictedJobs.get(cacheKey));
		}
	}

	private boolean hasCachedResult(RemoteDensityResultCache.Key cacheKey) {
		synchronized (resultStateLock) {
			return currentCacheKey(cacheKey) && resultCache.contains(cacheKey);
		}
	}

	private boolean storeCachedResult(
		RemoteDensityResultCache.Key cacheKey,
		TerrainDensityResult result,
		String source
	) {
		synchronized (resultStateLock) {
			if (!currentCacheKey(cacheKey)) {
				return false;
			}
			if (startedTerrain.contains(cacheKey,System.nanoTime())) {
				lateResultsDiscarded.increment();return false;
			}
			resultCache.put(cacheKey, result);
			WorldgenAssist.LOGGER.info(
				"[CAWG] cache.store chunk={},{} samples={} entries={} capacity={} generation={} source={}",
				cacheKey.chunkX(),
				cacheKey.chunkZ(),
				result.densityCount(),
				resultCache.size(),
				config.cacheEntries(),
				cacheKey.generation(),
				source
			);
			return true;
		}
	}

	private CompletableFuture<ChunkAccess> applyRemoteDensity(
		ChunkAccess chunk,
		TerrainDensityJob job,
		TerrainDensityResult result,
		RemoteDensityResultCache.Key cacheKey,
		UUID ownerId,
		RemoteWorldgenEligibility.EligibleContext eligibleContext,
		Supplier<CompletableFuture<ChunkAccess>> localFallback,
		long startedNanos,
		String source
	) {
		ResultTransferMetrics metrics = transferMetrics.remove(job.identity().jobId());
		try {
			RemoteDensityField densityField = new RemoteDensityField(job, result, eligibleContext.settings().value(),
				eligibleContext.level().getChunkSource().randomState());
			RemoteDensityTarget target = (RemoteDensityTarget) chunk;
			synchronized (resultStateLock) {
				if (!currentCacheKey(cacheKey)) {
					throw new IllegalStateException("Remote density context was invalidated before application");
				}
				target.worldgenAssist$installRemoteDensity(densityField);
				startedTerrain.mark(cacheKey,System.nanoTime());
			}
			// Direct results already have a waiting chunk; retaining another full
			// density volume would only consume memory. Prediction owns cache writes.
			if (source.equals("remote")) logResultReceived(job, result, metrics, startedNanos, source);
			long applyStartedNanos = System.nanoTime();
			CompletableFuture<ChunkAccess> applied = invokeFallback(localFallback);
			return applied.whenComplete((generated, error) -> {
				target.worldgenAssist$clearRemoteDensity(job.identity().jobId());
				if (error == null) {
					if (ownerId != null && source.equals("remote")) coordinator.recordValidated(ownerId);
					WorldgenAssist.LOGGER.info(
						"[CAWG] job.complete id={} source={} apply_ms={} total_ms={} work_kind={} remote_samples={} grid_samples={} decision_samples={}",
						job.identity().jobId(),
						source,
						(System.nanoTime() - applyStartedNanos) / 1_000_000.0,
						(System.nanoTime() - startedNanos) / 1_000_000.0,
						job.workKind(), densityField.remoteSamplesServed(), densityField.gridSamplesServed(),densityField.decisionSamplesServed()
					);
				}
			});
		} catch (RuntimeException | Error error) {
			synchronized (resultStateLock) {
				resultCache.remove(cacheKey);
			}
			WorldgenAssist.LOGGER.warn("[CAWG] job.apply_rejected id={}", job.identity().jobId(), error);
			return invokeFallback(localFallback);
		}
	}

	private void quarantineWorker(UUID ownerId, UUID failedJobId, String source, String reason) {
		invalidateOwner(ownerId);
		int cancelled = coordinator.quarantine(ownerId);
		predictor.remove(ownerId);
		WorldgenAssist.LOGGER.warn(
			"[CAWG] worker.quarantined owner={} failed_job={} source={} reason={} cancelled_jobs={}",
			ownerId,
			failedJobId,
			source,
			reason,
			cancelled
		);
	}

	private static void logResultReceived(
		TerrainDensityJob job,
		TerrainDensityResult result,
		ResultTransferMetrics metrics,
		long startedNanos,
		String source
	) {
		long receivedNanos = metrics == null ? System.nanoTime() : metrics.receivedNanos();
		long encodeNanos = metrics == null ? 0L : metrics.clientEncodeNanos();
		long decodeNanos = metrics == null ? 0L : metrics.serverDecodeNanos();
		int rawBytes = metrics == null ? Math.multiplyExact(result.densityCount(), Double.BYTES) : metrics.rawBytes();
		int encodedBytes = metrics == null ? 0 : metrics.encodedBytes();
		String encoding = metrics == null ? "CACHE" : metrics.encoding().name();
		double compressionRatio = metrics == null ? 0.0 : (double)encodedBytes / rawBytes;
		long unattributedNanos = Math.max(
			0L,
			receivedNanos - startedNanos - result.clientComputeNanos() - encodeNanos
		);
		WorldgenAssist.LOGGER.info(
			"[CAWG] job.result_received id={} source={} rtt_ms={} client_compute_ms={} client_encode_ms={} server_decode_ms={} unattributed_ms={} samples={} encoding={} raw_bytes={} encoded_bytes={} compression_ratio={}",
			job.identity().jobId(),
			source,
			(receivedNanos - startedNanos) / 1_000_000.0,
			result.clientComputeNanos() / 1_000_000.0,
			encodeNanos / 1_000_000.0,
			decodeNanos / 1_000_000.0,
			unattributedNanos / 1_000_000.0,
			result.densityCount(),
			encoding,
			rawBytes,
			encodedBytes,
			compressionRatio
		);
	}

	private static CompletableFuture<ChunkAccess> invokeFallback(Supplier<CompletableFuture<ChunkAccess>> localFallback) {
		try {
			return localFallback.get();
		} catch (Throwable error) {
			return CompletableFuture.failedFuture(error);
		}
	}

	private static Throwable rootCause(Throwable error) {
		Throwable current = error;
		while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
			&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private record ResultTransferMetrics(
		TerrainDensityResultEnvelope.Encoding encoding,
		int rawBytes,
		int encodedBytes,
		long clientEncodeNanos,
		long serverDecodeNanos, long receivedNanos
	) {
	}

	private record PredictionAttempt(
		UUID ownerId,
		TerrainDensityJob job,
		CompletableFuture<TerrainDensityResult> result, String source,
		CompletableFuture<TerrainDensityResult> validation, java.util.concurrent.atomic.AtomicBoolean abandoned,
		long startedNanos
	) {
	}
	private record AheadAvailability(Optional<TerrainDensityResult> cached, PredictionAttempt attempt) { }
	private record PrefetchDispatch(MinecraftServer server, long generation) { }
}
