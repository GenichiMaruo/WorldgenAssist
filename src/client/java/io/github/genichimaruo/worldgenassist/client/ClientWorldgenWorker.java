package io.github.genichimaruo.worldgenassist.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.WorldgenPlatform;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;

public final class ClientWorldgenWorker {
	private static final int WORKER_THREADS = 1;
	private static final String CORRUPT_RESULT_TEST_SYSTEM_PROPERTY = "worldgen_assist.client.test_corrupt_density";
	private static final String CORRUPT_RESULT_TEST_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_CLIENT_TEST_CORRUPT_DENSITY";

	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
		WORKER_THREADS,
		WORKER_THREADS,
		0L,
		TimeUnit.MILLISECONDS,
		new ArrayBlockingQueue<>(WORKER_THREADS),
		runnable -> {
			Thread thread = new Thread(runnable, "CAWG-RemoteWorldgen-1");
			thread.setDaemon(true);
			return thread;
		},
		new ThreadPoolExecutor.AbortPolicy()
	);
	private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();
	private final HolderLookup.Provider worldgenRegistries;
	private final ClientWorkerTransport transport;
	private final boolean corruptResultsForAdversarialTest;
	private volatile boolean accepted;

	public ClientWorldgenWorker(ClientWorkerTransport transport) {
		this.transport = transport;
		long startedNanos = System.nanoTime();
		this.worldgenRegistries = VanillaRegistries.createWorldLookup();
		this.corruptResultsForAdversarialTest = WorldgenPlatform.isDevelopment()
			&& (Boolean.getBoolean(CORRUPT_RESULT_TEST_SYSTEM_PROPERTY)
				|| "true".equalsIgnoreCase(System.getenv(CORRUPT_RESULT_TEST_ENVIRONMENT_VARIABLE)));
		WorldgenAssist.LOGGER.info(
			"[CAWG] worker.worldgen_registry_ready elapsed_ms={}",
			(System.nanoTime() - startedNanos) / 1_000_000.0
		);
		if (corruptResultsForAdversarialTest) {
			WorldgenAssist.LOGGER.warn(
				"[CAWG] adversarial_test.corrupt_density_enabled property={} environment_variable={}",
				CORRUPT_RESULT_TEST_SYSTEM_PROPERTY,
				CORRUPT_RESULT_TEST_ENVIRONMENT_VARIABLE
			);
		}
	}

	public void onAccepted(WorkerAcceptedPayload payload) {
			accepted = payload.accepted();
			WorldgenAssist.LOGGER.info(
				"[CAWG] worker.handshake status={} max_in_flight={}",
				payload.status(),
				payload.maxInFlightJobs()
			);
	}

	public void onJoin() {
			accepted = false;
			boolean participation = ClientSettings.participation();
			boolean channelPresent = transport.canSendHello();
			if (!participation || !channelPresent) {
				WorldgenAssist.LOGGER.info("[CAWG] worker.hello_skipped participation={} channel_present={}", participation, channelPresent);
				return;
			}
			WorldgenAssist.LOGGER.info("[CAWG] worker.hello_sent protocol={}", WorldgenProtocolVersion.CURRENT);
			transport.send(new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT, WORKER_THREADS, WorldgenPlatform.version()));
	}

	public void onDisconnect() {
			accepted = false;
			attempts.values().forEach(Attempt::cancel);
			attempts.clear();
	}

	public void handleRequest(Minecraft client, TerrainDensityJob job) {
		if (!accepted || client.getConnection() == null || client.level == null) {
			sendFailure(client, job, TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
			return;
		}
		Identifier currentDimension = client.level.dimension().identifier();
		AtomicBoolean cancelled = new AtomicBoolean();
		FutureTask<Void> task = new FutureTask<>(() -> {
			try {
				WorldgenAssist.LOGGER.info(
					"[CAWG] job.client_started id={} chunk={},{}",
					job.identity().jobId(),
					job.identity().chunkX(),
					job.identity().chunkZ()
				);
				TerrainDensityResult result = ClientTerrainDensityComputer.compute(worldgenRegistries, currentDimension, job);
				if (corruptResultsForAdversarialTest) {
					result = corruptEveryDensity(result);
				}
				TerrainDensityResultEnvelope envelope = TerrainDensityResultEnvelope.encode(result);
				WorldgenAssist.LOGGER.info(
					"[CAWG] job.client_complete id={} compute_ms={} encode_ms={} samples={} encoding={} raw_bytes={} encoded_bytes={}",
					job.identity().jobId(),
					result.clientComputeNanos() / 1_000_000.0,
					envelope.clientEncodeNanos() / 1_000_000.0,
					result.densityCount(),
					envelope.encoding(),
					envelope.rawDensityBytes(),
					envelope.encodedDensityBytes()
				);
				client.execute(() -> finish(client, job.identity().jobId(), cancelled, new TerrainJobResultPayload(envelope)));
			} catch (ClientTerrainDensityComputer.RejectedJobException exception) {
				client.execute(() -> finishFailure(client, job, cancelled, exception.reason()));
			} catch (CancellationException exception) {
				attempts.remove(job.identity().jobId());
			} catch (RuntimeException | Error error) {
				WorldgenAssist.LOGGER.warn("[CAWG] job.client_failed id={} chunk={},{}", job.identity().jobId(), job.identity().chunkX(), job.identity().chunkZ(), error);
				client.execute(() -> finishFailure(client, job, cancelled, TerrainJobFailurePayload.Reason.COMPUTE_FAILED));
			}
			return null;
		});
		Attempt attempt = new Attempt(cancelled, task);
		if (attempts.putIfAbsent(job.identity().jobId(), attempt) != null) {
			sendFailure(client, job, TerrainJobFailurePayload.Reason.BUSY);
			return;
		}
		try {
			executor.execute(task);
		} catch (RejectedExecutionException exception) {
			attempts.remove(job.identity().jobId(), attempt);
			sendFailure(client, job, TerrainJobFailurePayload.Reason.BUSY);
		}
	}

	private void finish(Minecraft client, UUID jobId, AtomicBoolean cancelled, TerrainJobResultPayload result) {
		attempts.remove(jobId);
		if (!cancelled.get() && client.getConnection() != null) {
			transport.send(result);
		}
	}

	private void finishFailure(
		Minecraft client,
		TerrainDensityJob job,
		AtomicBoolean cancelled,
		TerrainJobFailurePayload.Reason reason
	) {
		attempts.remove(job.identity().jobId());
		if (!cancelled.get()) {
			sendFailure(client, job, reason);
		}
	}

	private void sendFailure(Minecraft client, TerrainDensityJob job, TerrainJobFailurePayload.Reason reason) {
		if (client.getConnection() != null) {
			transport.send(new TerrainJobFailurePayload(job.identity(), reason));
		}
	}

	public void cancel(TerrainJobCancelPayload payload) {
		Attempt attempt = attempts.remove(payload.identity().jobId());
		if (attempt != null) {
			attempt.cancel();
		}
	}

	private static TerrainDensityResult corruptEveryDensity(TerrainDensityResult result) {
		double[] densities = result.densities();
		for (int index = 0; index < densities.length; index++) {
			densities[index] = densities[index] >= 0.0
				? Math.nextDown(densities[index])
				: Math.nextUp(densities[index]);
		}
		return new TerrainDensityResult(result.identity(), densities, result.clientComputeNanos());
	}

	private record Attempt(AtomicBoolean cancelled, FutureTask<Void> task) {
		private void cancel() {
			cancelled.set(true);
			task.cancel(true);
		}
	}
}
