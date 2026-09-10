package io.github.genichimaruo.worldgenassist.server;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import java.util.function.Supplier;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.mixin.ChunkAccessNoiseChunkAccessor;
import io.github.genichimaruo.worldgenassist.mixin.NoiseBasedChunkGeneratorInvoker;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

public final class RemoteWorldgenManager {
	private static RemoteWorldgenManager instance;

	private final RemoteWorldgenConfig config;
	private final RemoteJobCoordinator coordinator;
	private final ScheduledExecutorService timeoutWatchdog;
	private final ThreadPoolExecutor resultDecoder;
	private final RemoteDensityResultCache resultCache;
	private final PlayerOwnedChunkPredictor predictor = new PlayerOwnedChunkPredictor();
	private final Map<ResourceKey<Level>, WorldgenContextFingerprint> contextFingerprints = new ConcurrentHashMap<>();
	private final Map<UUID, ResultTransferMetrics> transferMetrics = new ConcurrentHashMap<>();
	private final Map<RemoteDensityResultCache.Key, PredictionAttempt> predictedJobs = new ConcurrentHashMap<>();
	private final AtomicLong cacheGeneration = new AtomicLong();
	private final Object resultStateLock = new Object();
	private final SecureRandom validationRandom = new SecureRandom();
	private long predictionTicks;
	private volatile MinecraftServer server;

	private RemoteWorldgenManager(RemoteWorldgenConfig config) {
		this.config = config;
		this.coordinator = new RemoteJobCoordinator(config, new FabricRemoteJobSender(() -> server));
		this.resultCache = new RemoteDensityResultCache(config.cacheEntries());
		this.timeoutWatchdog = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "CAWG-RemoteTimeout");
			thread.setDaemon(true);
			return thread;
		});
		this.resultDecoder = new ThreadPoolExecutor(
			1,
			1,
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

	public static synchronized void register(RemoteWorldgenConfig config) {
		if (instance != null) {
			throw new IllegalStateException("RemoteWorldgenManager is already registered");
		}
		RemoteWorldgenManager manager = new RemoteWorldgenManager(config);
		instance = manager;
		manager.registerHandlers();
		manager.startTimeoutWatchdog();
	}

	public static CompletableFuture<ChunkAccess> generateNoiseOrFallback(
		WorldGenContext context,
		ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk,
		Supplier<CompletableFuture<ChunkAccess>> localFallback
	) {
		Optional<CompletableFuture<ChunkAccess>> fixture = SeededLeafFixtureManager.tryGenerate(context, step, chunks, chunk, localFallback);
		if (fixture.isPresent()) { return fixture.orElseThrow(); }
		RemoteWorldgenManager manager = instance;
		return manager == null
			? invokeFallback(localFallback)
			: manager.generate(context, step, chunks, chunk, localFallback);
	}

	private void registerHandlers() {
		ServerPlayNetworking.registerGlobalReceiver(WorkerHelloPayload.TYPE, (payload, context) -> {
			WorkerAcceptedPayload response = coordinator.handleHello(context.player().getUUID(), payload);
			context.responseSender().sendPacket(response);
			WorldgenAssist.LOGGER.info(
				"[CAWG] worker.register owner={} status={} requested_parallel={} version={}",
				context.player().getUUID(),
				response.status(),
				payload.maxParallelJobs(),
				payload.implementationVersion()
			);
		});
		ServerPlayNetworking.registerGlobalReceiver(TerrainJobResultPayload.TYPE, (payload, context) -> {
			UUID ownerId = context.player().getUUID();
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
				resultDecoder.execute(() -> decodeResult(ownerId, envelope));
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
		});
		ServerPlayNetworking.registerGlobalReceiver(TerrainJobFailurePayload.TYPE, (payload, context) -> {
			PendingTerrainJobRegistry.ResponseStatus status = coordinator.handleFailure(context.player().getUUID(), payload);
			WorldgenAssist.LOGGER.info(
				"[CAWG] job.client_rejected id={} owner={} reason={} status={}",
				payload.identity().jobId(),
				context.player().getUUID(),
				payload.reason(),
				status
			);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((listener, currentServer) -> {
			int cancelled = coordinator.disconnect(listener.player.getUUID());
			predictor.remove(listener.player.getUUID());
			if (cancelled > 0) {
				WorldgenAssist.LOGGER.info("[CAWG] worker.disconnect owner={} cancelled_jobs={}", listener.player.getUUID(), cancelled);
			}
		});
		ServerLifecycleEvents.SERVER_STARTING.register(currentServer -> {
			server = currentServer;
			contextFingerprints.clear();
			clearResultState("server_starting");
		});
		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((currentServer, resourceManager) -> {
			int cancelled = coordinator.cancelAllForReload();
			contextFingerprints.clear();
			clearResultState("datapack_reload");
			if (cancelled > 0) {
				WorldgenAssist.LOGGER.info("[CAWG] datapack_reload cancelled_jobs={}", cancelled);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(currentServer -> {
			int cancelled = coordinator.shutdown();
			contextFingerprints.clear();
			clearResultState("server_stopping");
			if (cancelled > 0) {
				WorldgenAssist.LOGGER.info("[CAWG] server.shutdown cancelled_jobs={}", cancelled);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(currentServer -> server = null);
		ServerTickEvents.END_SERVER_TICK.register(currentServer -> {
			int expired = coordinator.expireTimedOut();
			if (expired > 0) {
				logTimeout(expired, "server_tick");
			}
			if (config.predictionEnabled() && ++predictionTicks % config.predictionIntervalTicks() == 0L) {
				predictForSoleWorker(currentServer);
			}
		});
	}

	private void clearResultState(String reason) {
		long generation;
		int cached;
		int metrics;
		int predictions;
		synchronized (resultStateLock) {
			generation = cacheGeneration.incrementAndGet();
			cached = resultCache.clear();
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

	private void decodeResult(UUID ownerId, TerrainDensityResultEnvelope envelope) {
		long startedNanos = System.nanoTime();
		try {
			TerrainDensityResult result = envelope.decode();
			long decodeNanos = System.nanoTime() - startedNanos;
			transferMetrics.put(
				envelope.identity().jobId(),
				new ResultTransferMetrics(
					envelope.encoding(),
					envelope.rawDensityBytes(),
					envelope.encodedDensityBytes(),
					envelope.clientEncodeNanos(),
					decodeNanos
				)
			);
			PendingTerrainJobRegistry.ResponseStatus status = coordinator.completeClaimedResult(ownerId, result);
			if (status == PendingTerrainJobRegistry.ResponseStatus.ACCEPTED) {
				WorldgenAssist.LOGGER.info(
					"[CAWG] job.result_decoded id={} encoding={} raw_bytes={} encoded_bytes={} decode_ms={}",
					envelope.identity().jobId(),
					envelope.encoding(),
					envelope.rawDensityBytes(),
					envelope.encodedDensityBytes(),
					decodeNanos / 1_000_000.0
				);
			} else {
				transferMetrics.remove(envelope.identity().jobId());
				WorldgenAssist.LOGGER.warn(
					"[CAWG] job.result_rejected id={} owner={} status={}",
					envelope.identity().jobId(),
					ownerId,
					status
				);
			}
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

	private void predictForSoleWorker(MinecraftServer currentServer) {
		Optional<UUID> owner = coordinator.soleWorkerOwner();
		if (owner.isEmpty()) {
			return;
		}
		ServerPlayer player = currentServer.getPlayerList().getPlayer(owner.get());
		if (player == null || player.isChangingDimension()) {
			predictor.remove(owner.get());
			return;
		}
		int effectiveViewDistance = PlayerOwnedChunkPredictor.effectiveViewDistance(
			player.requestedViewDistance(),
			currentServer.getPlayerList().getViewDistance()
		);
		Optional<PlayerOwnedChunkPredictor.Prediction> prediction = predictor.observe(
			owner.get(),
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
		PlayerOwnedChunkPredictor.Prediction targetPrediction = selected;
		ChunkPos chunkPos = new ChunkPos(targetPrediction.chunkX(), targetPrediction.chunkZ());
		try {
			Optional<RemoteWorldgenEligibility.SpeculativeContext> eligible = RemoteWorldgenEligibility.evaluatePrediction(level);
			if (eligible.isEmpty()) {
				return;
			}
			RemoteWorldgenEligibility.SpeculativeContext speculative = eligible.get();
			WorldgenContextFingerprint fingerprint = contextFingerprints.computeIfAbsent(
				level.dimension(),
				ignored -> WorldgenContextFingerprintFactory.create(level, speculative.generator())
			);
			var noiseSettings = speculative.settings().unwrapKey().orElseThrow().identifier();
			RemoteDensityResultCache.Key cacheKey = createCacheKey(
				level,
				chunkPos,
				fingerprint,
				noiseSettings,
				speculative.noise()
			);
			if (hasCachedResult(cacheKey) || predictedJobs.containsKey(cacheKey)) {
				return;
			}

			long startedNanos = System.nanoTime();
			Optional<RemoteJobCoordinator.Submission> submission = coordinator.trySubmitForOwner(
				targetPrediction.ownerId(),
				targetPrediction.dimension(),
				targetPrediction.chunkX(),
				targetPrediction.chunkZ(),
				fingerprint,
				identity -> createPredictionJob(identity, speculative, noiseSettings)
			);
			if (submission.isEmpty()) {
				return;
			}

			RemoteJobCoordinator.Submission remote = submission.get();
			CompletableFuture<TerrainDensityResult> prepared = remote.result().thenApply(result ->
				completePrediction(remote.ownerId(), remote.job(), result, cacheKey, speculative, startedNanos)
			);
			PredictionAttempt attempt = new PredictionAttempt(targetPrediction.ownerId(), remote.job(), prepared);
			predictedJobs.put(cacheKey, attempt);
			prepared.whenComplete((result, error) -> {
				predictedJobs.remove(cacheKey, attempt);
				if (error != null) {
					transferMetrics.remove(remote.job().identity().jobId());
					WorldgenAssist.LOGGER.info(
						"[CAWG] prediction.failed id={} owner={} chunk={},{} reason={}",
						remote.job().identity().jobId(),
						targetPrediction.ownerId(),
						targetPrediction.chunkX(),
						targetPrediction.chunkZ(),
						rootCause(error).getClass().getSimpleName()
					);
				}
			});
			WorldgenAssist.LOGGER.info(
				"[CAWG] prediction.sent id={} owner={} chunk={},{} direction={},{} effective_view={} lead={} samples={}",
				remote.job().identity().jobId(),
				targetPrediction.ownerId(),
				targetPrediction.chunkX(),
				targetPrediction.chunkZ(),
				targetPrediction.directionX(),
				targetPrediction.directionZ(),
				targetPrediction.effectiveViewDistance(),
				config.predictionLeadChunks(),
				remote.job().sampleCount()
			);
		} catch (RuntimeException error) {
			WorldgenAssist.LOGGER.warn(
				"[CAWG] prediction.rejected owner={} chunk={},{} reason={}",
				targetPrediction.ownerId(),
				targetPrediction.chunkX(),
				targetPrediction.chunkZ(),
				error.getClass().getSimpleName(),
				error
			);
		}
	}

	private TerrainDensityResult completePrediction(
		UUID ownerId,
		TerrainDensityJob job,
		TerrainDensityResult result,
		RemoteDensityResultCache.Key cacheKey,
		RemoteWorldgenEligibility.SpeculativeContext speculative,
		long startedNanos
	) {
		new RemoteDensityField(job, result);
		validateRemoteDensity(
			ownerId,
			job,
			result,
			speculative.level(),
			speculative.settings().value(),
			speculative.noise(),
			"prediction"
		);
		if (!storeCachedResult(cacheKey, result, "prediction")) {
			throw new java.util.concurrent.CancellationException("Prediction context was invalidated before completion");
		}
		ResultTransferMetrics metrics = transferMetrics.remove(job.identity().jobId());
		logResultReceived(job, result, metrics, startedNanos, "prediction");
		WorldgenAssist.LOGGER.info(
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
		if (!config.remoteExecutionEnabled()) {
			return invokeFallback(localFallback);
		}
		Optional<RemoteWorldgenEligibility.EligibleContext> eligible = RemoteWorldgenEligibility.evaluate(context, step, chunks, chunk);
		if (eligible.isEmpty()) {
			return invokeFallback(localFallback);
		}
		RemoteWorldgenEligibility.EligibleContext eligibleContext = eligible.get();
		Optional<RemoteJobCoordinator.Submission> submission;
		RemoteDensityResultCache.Key cacheKey;
		long startedNanos = System.nanoTime();
		try {
			WorldgenContextFingerprint fingerprint = contextFingerprints.computeIfAbsent(
				eligibleContext.level().dimension(),
				ignored -> WorldgenContextFingerprintFactory.create(eligibleContext.level(), eligibleContext.generator())
			);
			chunk.getOrCreateNoiseChunk(candidate -> ((NoiseBasedChunkGeneratorInvoker)(Object)eligibleContext.generator())
				.worldgenAssist$createNoiseChunk(
					candidate,
					eligibleContext.structureManager(),
					eligibleContext.blender(),
					eligibleContext.level().getChunkSource().randomState()
				));
			var noiseSettings = eligibleContext.settings().unwrapKey().orElseThrow().identifier();
			cacheKey = createCacheKey(
				eligibleContext.level(),
				chunk.getPos(),
				fingerprint,
				noiseSettings,
				eligibleContext.noise()
			);
			Optional<double[]> cached = cachedResult(cacheKey);
			if (cached.isPresent()) {
				TerrainJobIdentity identity = new TerrainJobIdentity(
					WorldgenProtocolVersion.CURRENT,
					UUID.randomUUID(),
					cacheKey.dimension(),
					cacheKey.chunkX(),
					cacheKey.chunkZ(),
					cacheKey.contextFingerprint()
				);
				TerrainDensityJob job = createJob(identity, eligibleContext, noiseSettings);
				TerrainDensityResult result = new TerrainDensityResult(identity, cached.orElseThrow(), 0L);
				WorldgenAssist.LOGGER.info(
					"[CAWG] cache.hit id={} chunk={},{} samples={} entries={}",
					identity.jobId(),
					identity.chunkX(),
					identity.chunkZ(),
					result.densityCount(),
					resultCache.size()
				);
				return applyRemoteDensity(chunk, job, result, cacheKey, null, eligibleContext, false, localFallback, startedNanos, "cache");
			}
			PredictionAttempt prediction = predictedJobs.get(cacheKey);
			if (prediction != null) {
				WorldgenAssist.LOGGER.info(
					"[CAWG] prediction.join id={} owner={} chunk={},{}",
					prediction.job().identity().jobId(),
					prediction.ownerId(),
					cacheKey.chunkX(),
					cacheKey.chunkZ()
				);
				return prediction.result().handle((result, error) -> {
					if (error != null) {
						return invokeFallback(localFallback);
					}
					return applyRemoteDensity(
						chunk,
						prediction.job(),
						result,
						cacheKey,
						prediction.ownerId(),
						eligibleContext,
						false,
						localFallback,
						startedNanos,
						"prediction"
					);
				}).thenCompose(future -> future);
			}
			submission = coordinator.trySubmit(
				eligibleContext.level().dimension().identifier(),
				chunk.getPos().x(),
				chunk.getPos().z(),
				fingerprint,
				identity -> createJob(identity, eligibleContext, noiseSettings)
			);
		} catch (RuntimeException error) {
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
			return invokeFallback(localFallback);
		}

		RemoteJobCoordinator.Submission remote = submission.get();
		WorldgenAssist.LOGGER.info(
			"[CAWG] cache.miss chunk={},{} entries={} capacity={}",
			cacheKey.chunkX(),
			cacheKey.chunkZ(),
			resultCache.size(),
			config.cacheEntries()
		);
		WorldgenAssist.LOGGER.info(
			"[CAWG] job.sent id={} chunk={},{} samples={} timeout_ms={}",
			remote.job().identity().jobId(),
			remote.job().identity().chunkX(),
			remote.job().identity().chunkZ(),
			remote.job().sampleCount(),
			config.jobTimeout().toMillis()
		);
		return remote.result().handle((result, error) -> {
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
				cacheKey,
				remote.ownerId(),
				eligibleContext,
				true,
				localFallback,
				startedNanos,
				"remote"
			);
		}).thenCompose(future -> future);
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
			eligibleContext.noise().getCellWidth(),
			eligibleContext.noise().getCellHeight()
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
			speculative.noise().getCellWidth(),
			speculative.noise().getCellHeight()
		);
	}

	private RemoteDensityResultCache.Key createCacheKey(
		ServerLevel level,
		ChunkPos chunkPos,
		WorldgenContextFingerprint fingerprint,
		net.minecraft.resources.Identifier noiseSettings,
		NoiseSettings noise
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
			noise.getCellWidth(),
			noise.getCellHeight()
		);
	}

	private Optional<double[]> cachedResult(RemoteDensityResultCache.Key cacheKey) {
		synchronized (resultStateLock) {
			return cacheKey.generation() == cacheGeneration.get() ? resultCache.get(cacheKey) : Optional.empty();
		}
	}

	private boolean hasCachedResult(RemoteDensityResultCache.Key cacheKey) {
		synchronized (resultStateLock) {
			return cacheKey.generation() == cacheGeneration.get() && resultCache.contains(cacheKey);
		}
	}

	private boolean storeCachedResult(
		RemoteDensityResultCache.Key cacheKey,
		TerrainDensityResult result,
		String source
	) {
		synchronized (resultStateLock) {
			if (cacheKey.generation() != cacheGeneration.get()) {
				return false;
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
		boolean cacheResult,
		Supplier<CompletableFuture<ChunkAccess>> localFallback,
		long startedNanos,
		String source
	) {
		ResultTransferMetrics metrics = transferMetrics.remove(job.identity().jobId());
		try {
			RemoteDensityField densityField = new RemoteDensityField(job, result);
			if (cacheResult) {
				validateRemoteDensity(
					ownerId,
					job,
					result,
					eligibleContext.level(),
					eligibleContext.settings().value(),
					eligibleContext.noise(),
					source
				);
			}
			NoiseChunk noiseChunk = ((ChunkAccessNoiseChunkAccessor)chunk).worldgenAssist$getNoiseChunk();
			if (!(noiseChunk instanceof RemoteDensityTarget target)) {
				throw new IllegalStateException("Chunk has no installable NoiseChunk at the NOISE stage");
			}
			synchronized (resultStateLock) {
				if (cacheKey.generation() != cacheGeneration.get()) {
					throw new IllegalStateException("Remote density context was invalidated before application");
				}
				target.worldgenAssist$installRemoteDensity(densityField);
			}
			if (cacheResult) {
				storeCachedResult(cacheKey, result, source);
			}
			logResultReceived(job, result, metrics, startedNanos, source);
			long applyStartedNanos = System.nanoTime();
			CompletableFuture<ChunkAccess> applied = invokeFallback(localFallback);
			return applied.whenComplete((generated, error) -> {
				target.worldgenAssist$clearRemoteDensity(job.identity().jobId());
				if (error == null) {
					WorldgenAssist.LOGGER.info(
						"[CAWG] job.complete id={} source={} apply_ms={} total_ms={}",
						job.identity().jobId(),
						source,
						(System.nanoTime() - applyStartedNanos) / 1_000_000.0,
						(System.nanoTime() - startedNanos) / 1_000_000.0
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

	private void validateRemoteDensity(
		UUID ownerId,
		TerrainDensityJob job,
		TerrainDensityResult result,
		ServerLevel level,
		net.minecraft.world.level.levelgen.NoiseGeneratorSettings settings,
		NoiseSettings noise,
		String source
	) {
		if (config.validationSampleCells() == 0) {
			return;
		}
		RemoteDensityValidator.ValidationMetrics metrics;
		try {
			metrics = RemoteDensityValidator.validate(
				job,
				result,
				config.validationSampleCells(),
				level.getChunkSource().randomState(),
				settings,
				noise,
				validationRandom
			);
		} catch (RemoteDensityValidator.RemoteDensityValidationException error) {
			if (ownerId != null) {
				quarantineWorker(ownerId, job.identity().jobId(), source, "density_mismatch");
			}
			throw error;
		}
		WorldgenAssist.LOGGER.info(
			"[CAWG] job.validation_complete id={} source={} sampled_cells={} sampled_values={} validation_ms={}",
			job.identity().jobId(),
			source,
			metrics.sampledCells(),
			metrics.sampledValues(),
			metrics.elapsedNanos() / 1_000_000.0
		);
	}

	private void quarantineWorker(UUID ownerId, UUID failedJobId, String source, String reason) {
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
		long receivedNanos = System.nanoTime();
		long encodeNanos = metrics == null ? 0L : metrics.clientEncodeNanos();
		long decodeNanos = metrics == null ? 0L : metrics.serverDecodeNanos();
		int rawBytes = metrics == null ? Math.multiplyExact(result.densityCount(), Double.BYTES) : metrics.rawBytes();
		int encodedBytes = metrics == null ? 0 : metrics.encodedBytes();
		String encoding = metrics == null ? "CACHE" : metrics.encoding().name();
		double compressionRatio = metrics == null ? 0.0 : (double)encodedBytes / rawBytes;
		long estimatedTransferNanos = Math.max(
			0L,
			receivedNanos - startedNanos - result.clientComputeNanos() - encodeNanos - decodeNanos
		);
		WorldgenAssist.LOGGER.info(
			"[CAWG] job.result_received id={} source={} rtt_ms={} client_compute_ms={} client_encode_ms={} server_decode_ms={} estimated_transfer_ms={} samples={} encoding={} raw_bytes={} encoded_bytes={} compression_ratio={}",
			job.identity().jobId(),
			source,
			(receivedNanos - startedNanos) / 1_000_000.0,
			result.clientComputeNanos() / 1_000_000.0,
			encodeNanos / 1_000_000.0,
			decodeNanos / 1_000_000.0,
			estimatedTransferNanos / 1_000_000.0,
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
		long serverDecodeNanos
	) {
	}

	private record PredictionAttempt(
		UUID ownerId,
		TerrainDensityJob job,
		CompletableFuture<TerrainDensityResult> result
	) {
	}
}
