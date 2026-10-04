package io.github.genichimaruo.worldgenassist.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
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
	private static final String CORRUPT_RESULT_TEST_SYSTEM_PROPERTY = "worldgen_assist.client.test_corrupt_density";
	private static final String CORRUPT_RESULT_TEST_ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_CLIENT_TEST_CORRUPT_DENSITY";

	private final int workerThreads;
	private final ClientWorkExecutor executor;
	private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();
	private final HolderLookup.Provider worldgenRegistries;
	private volatile ClientTerrainDensityComputer.Session densitySession;
	private volatile Identifier activeDimension;
	private final ClientWorkerTransport transport;
	private final boolean corruptResultsForAdversarialTest;
	private volatile boolean accepted;
	private volatile ClientReplySession replySession;
	private final ClientConnectionAdmission<Object> networkAdmission = new ClientConnectionAdmission<>();
	private Object requestConnection;

	public ClientWorldgenWorker(ClientWorkerTransport transport) {
		this.transport = transport;
		this.workerThreads = ClientSettings.workerThreads();
		this.executor = new ClientWorkExecutor(
			workerThreads,
			runnable -> {
				Thread thread = new Thread(runnable, "CAWG-RemoteWorldgen");
				thread.setDaemon(true);
				return thread;
			}
		);
		long startedNanos = System.nanoTime();
		this.worldgenRegistries = VanillaRegistries.createWorldLookup();
		this.densitySession = new ClientTerrainDensityComputer.Session(worldgenRegistries);
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

	public synchronized void onAccepted(WorkerAcceptedPayload payload) {
			accepted = payload.accepted();
			if (!accepted) revokeReplies();
			WorldgenAssist.LOGGER.info(
				"[CAWG] worker.handshake status={} max_in_flight={}",
				payload.status(),
				payload.maxInFlightJobs()
			);
	}

	public synchronized void onJoin() {
			networkAdmission.clear(); requestConnection = null;
			revokeReplies();
			densitySession = new ClientTerrainDensityComputer.Session(worldgenRegistries);
			activeDimension = null;
			accepted = false;
			boolean participation = ClientSettings.participation();
			boolean channelPresent = transport.canSendHello();
			if (!participation || !channelPresent) {
				WorldgenAssist.LOGGER.info("[CAWG] worker.hello_skipped participation={} channel_present={}", participation, channelPresent);
				return;
			}
			WorldgenAssist.LOGGER.info("[CAWG] worker.hello_sent protocol={} threads={}", WorldgenProtocolVersion.CURRENT, workerThreads);
			replySession = new ClientReplySession(transport.captureSender());
			// Advertise a bounded overlapping window. The executor retains one
			// worker-width of queue slack for the post-result handoff.
			transport.send(new WorkerHelloPayload(WorldgenProtocolVersion.CURRENT,
				executor.advertisedJobs(), WorldgenPlatform.version()));
	}

	public synchronized void onDisconnect() {
			networkAdmission.clear(); requestConnection = null;
			revokeReplies();
			densitySession = new ClientTerrainDensityComputer.Session(worldgenRegistries);
			activeDimension = null;
			accepted = false;
	}

	private void revokeReplies() {
		ClientReplySession previous = replySession;
		replySession = null;
		if (previous != null) previous.close();
		attempts.values().forEach(Attempt::cancel);
		attempts.clear();
		executor.purge();
	}

	public synchronized void handleRequest(Minecraft client, TerrainDensityJob job) {
		if (!prepareRequestContext(client)) {
			sendFailure(client, job, TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
			return;
		}
		submit(job, activeDimension, densitySession, replySession, System.nanoTime(), "main");
	}

	/** Called only on the client main thread; network admission reads captured values. */
	public synchronized boolean prepareRequestContext(Minecraft client) {
		if (!accepted || client.getConnection() == null || client.level == null) {
			return false;
		}
		Object connection = client.getConnection().getConnection();
		if (networkAdmission.suspended(connection)) return false;
		Identifier currentDimension = client.level.dimension().identifier();
		if (!currentDimension.equals(activeDimension) || requestConnection != connection) {
				revokeReplies();
				replySession = new ClientReplySession(transport.captureSender());
			densitySession = new ClientTerrainDensityComputer.Session(worldgenRegistries);
			activeDimension = currentDimension;
			requestConnection = connection;
			networkAdmission.bind(connection);
		}
		return replySession != null;
	}

	public synchronized boolean handleRequestFromNetwork(Object connection, TerrainDensityJob job, long receivedNanos) {
		return accepted && networkAdmission.dispatch(connection,
			() -> submit(job, activeDimension, densitySession, replySession, receivedNanos, "network"));
	}

	public synchronized void suspendNetworkContext(Object connection) {
		if (networkAdmission.suspend(connection)) {
			revokeReplies(); activeDimension = null;
			densitySession = new ClientTerrainDensityComputer.Session(worldgenRegistries);
		}
	}
	public synchronized void resumeNetworkContext(Object connection) { networkAdmission.resume(connection); }
	public synchronized void closeNetworkContext(Object connection) {
		if (networkAdmission.clear(connection)) {
			revokeReplies(); requestConnection = null; activeDimension = null; accepted = false;
		}
	}
	public synchronized boolean cancelFromNetwork(Object connection, TerrainJobCancelPayload payload) {
		return networkAdmission.dispatch(connection, () -> cancel(payload));
	}

	private void submit(TerrainDensityJob job, Identifier currentDimension,
		ClientTerrainDensityComputer.Session requestSession, ClientReplySession requestReplies,
		long requestedNanos, String requestPath) {
		if (requestReplies == null) return;
		AtomicBoolean cancelled = new AtomicBoolean();
		FutureTask<Void> task = new FutureTask<>(() -> {
			try {
				WorldgenAssist.LOGGER.info(
					"[CAWG] job.client_started id={} chunk={},{}",
					job.identity().jobId(),
					job.identity().chunkX(),
					job.identity().chunkZ()
				);
				long workerStarted = System.nanoTime();
				TerrainDensityResult result = ClientTerrainDensityComputer.compute(requestSession, currentDimension, job);
				if (corruptResultsForAdversarialTest) {
					result = corruptEveryDensity(result);
				}
				TerrainDensityResultEnvelope envelope = TerrainDensityResultEnvelope.encode(result, job.workKind());
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
				long encodedNanos = System.nanoTime();
				boolean sent;
				try {
					sent = requestReplies.send(new TerrainJobResultPayload(envelope), () -> !cancelled.get());
				} finally { attempts.remove(job.identity().jobId()); }
				if (sent) WorldgenAssist.LOGGER.info("[CAWG] job.client_dispatch id={} worker_queue_ms={} worker_total_ms={} send_wait_ms={} path=worker_direct request_path={}",
						job.identity().jobId(), (workerStarted - requestedNanos) / 1_000_000.0,
						(encodedNanos - workerStarted) / 1_000_000.0, (System.nanoTime() - encodedNanos) / 1_000_000.0, requestPath);
			} catch (ClientTerrainDensityComputer.RejectedJobException exception) {
				finishFailure(requestReplies, job, cancelled, exception.reason());
			} catch (CancellationException exception) {
				attempts.remove(job.identity().jobId());
			} catch (RuntimeException | Error error) {
				WorldgenAssist.LOGGER.warn("[CAWG] job.client_failed id={} chunk={},{}", job.identity().jobId(), job.identity().chunkX(), job.identity().chunkZ(), error);
				finishFailure(requestReplies, job, cancelled, TerrainJobFailurePayload.Reason.COMPUTE_FAILED);
			}
			return null;
		});
		Attempt attempt = new Attempt(cancelled, task);
		if (attempts.putIfAbsent(job.identity().jobId(), attempt) != null) {
			requestReplies.send(new TerrainJobFailurePayload(job.identity(), TerrainJobFailurePayload.Reason.BUSY), () -> true);
			return;
		}
		try {
			executor.execute(task);
		} catch (RejectedExecutionException exception) {
			attempts.remove(job.identity().jobId(), attempt);
			requestReplies.send(new TerrainJobFailurePayload(job.identity(), TerrainJobFailurePayload.Reason.BUSY), () -> true);
		}
	}

	private void finishFailure(
		ClientReplySession replies,
		TerrainDensityJob job,
		AtomicBoolean cancelled,
		TerrainJobFailurePayload.Reason reason
	) {
		attempts.remove(job.identity().jobId());
		replies.send(new TerrainJobFailurePayload(job.identity(), reason), () -> !cancelled.get());
	}

	private void sendFailure(Minecraft client, TerrainDensityJob job, TerrainJobFailurePayload.Reason reason) {
		if (client.getConnection() != null) {
			transport.send(new TerrainJobFailurePayload(job.identity(), reason));
		}
	}

	public synchronized void cancel(TerrainJobCancelPayload payload) {
		Attempt attempt = attempts.remove(payload.identity().jobId());
		if (attempt != null) {
			attempt.cancel();
			executor.remove(attempt.task());
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
