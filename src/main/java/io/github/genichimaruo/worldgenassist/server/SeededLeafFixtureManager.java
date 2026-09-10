package io.github.genichimaruo.worldgenassist.server;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId;
import io.github.genichimaruo.worldgenassist.common.SeededLeafFixtureConfig;
import io.github.genichimaruo.worldgenassist.mixin.ChunkAccessNoiseChunkAccessor;
import io.github.genichimaruo.worldgenassist.mixin.NoiseBasedChunkGeneratorInvoker;
import io.github.genichimaruo.worldgenassist.network.SeededLeafFixturePayloads;

/** Opt-in, fixed-public-world runtime owner. Never handles a private world seed. */
public final class SeededLeafFixtureManager {
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static volatile SeededLeafFixtureManager instance;
	private volatile Session session;
	private volatile Owner owner;
	private volatile Demand demand;
	private SeededLeafFixtureManager() { }

	public static void register() {
		if (!SeededLeafFixtureConfig.serverEnabled()) { return; }
		if (instance != null) { throw new IllegalStateException("Fixture manager already registered"); }
		SeededLeafFixtureManager manager = new SeededLeafFixtureManager();
		instance = manager;
		ServerLifecycleEvents.SERVER_STARTING.register(manager::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> manager.stop());
		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> {
			manager.demand = null;
			Session current = manager.session;
			if (current != null) {
				current.orchestrator.reload();
				WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.reload generation={}", current.authority.contextGeneration());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(manager::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
			Owner current = manager.owner;
			if (current != null && current.player == listener.player) { manager.disconnect(); }
		});
		ServerPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Hello.TYPE, (payload, context) -> {
			boolean accepted = manager.accept(context.player(), payload.protocol());
			context.responseSender().sendPacket(new SeededLeafFixturePayloads.Accepted(accepted));
			WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.handshake accepted={}", accepted);
		});
		ServerPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Result.TYPE, (payload, context) -> {
			Session current = manager.session;
			Owner active = manager.owner;
			if (current != null && active != null && active.player == context.player()) {
				current.exchange.receive(active.connection, payload.result());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Failure.TYPE, (payload, context) -> {
			Session current = manager.session;
			Owner active = manager.owner;
			if (current != null && active != null && active.player == context.player()) {
				current.exchange.fail(active.connection, payload.claim());
			}
		});
	}

	private void start(MinecraftServer server) {
		if (!SeededLeafFixtureConfig.permitsWorld(server.getWorldGenSettings().options().seed())) {
			WorldgenAssist.LOGGER.warn("[CAWG] seeded_fixture.blocked reason=world_not_public_fixture");
			return;
		}
		SeededLeafPersistentDisclosureLedger ledger = new SeededLeafPersistentDisclosureLedger(100_000);
		Path data = server.getWorldPath(LevelResource.DATA).toAbsolutePath().normalize();
		Path path = data.resolve(WorldgenAssist.MOD_ID).resolve("seeded_leaf_disclosure_budget.bin");
		if (ledger.open(path) != SeededLeafPersistentDisclosureLedger.OpenStatus.OPENED) {
			ledger.close();
			WorldgenAssist.LOGGER.warn("[CAWG] seeded_fixture.blocked reason=ledger_unavailable");
			return;
		}
		SecureRandom random = new SecureRandom();
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(1, 1, 100_000, 100_000,
			TIMEOUT, Duration.ofSeconds(30), System::nanoTime, UUID::randomUUID,
			() -> OpaqueWorldgenContextId.random(random), () -> SeededLeafJobAuthenticator.random(random), ledger);
		authority.start();
		SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(authority, 64, TIMEOUT);
		session = new Session(server, authority, ledger, orchestrator, new SeededLeafDispatchQueue(orchestrator));
		WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.started protocol=3 policy=public_fixture_only");
	}

	private boolean accept(ServerPlayer player, int protocol) {
		Session current = session;
		if (current == null || protocol != SeededLeafFixtureConfig.PROTOCOL
			|| current.server.getPlayerList().getPlayerCount() != 1
			|| !ServerPlayNetworking.canSend(player, SeededLeafFixturePayloads.Request.TYPE)
			|| !ServerPlayNetworking.canSend(player, SeededLeafFixturePayloads.Cancel.TYPE)) { return false; }
		Owner active = owner;
		if (active != null) { return active.player == player; }
		owner = new Owner(player, current.orchestrator.connect(player.getUUID()));
		return true;
	}

	private void tick(MinecraftServer server) {
		Session current = session;
		Owner active = owner;
		if (current == null || current.server != server) { return; }
		boolean multipleOrNoPlayers = server.getPlayerList().getPlayerCount() != 1;
		if (multipleOrNoPlayers && current.playerCountSuspended.compareAndSet(false, true)) {
			current.orchestrator.suspendAdmission();
			WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.player_count_suspended count={}", server.getPlayerList().getPlayerCount());
		} else if (!multipleOrNoPlayers && current.playerCountSuspended.compareAndSet(true, false)) {
			current.orchestrator.resumeAdmission();
			WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.player_count_resumed count=1");
		}
		if (active == null || server.getPlayerList().getPlayerCount() != 1) {
			demand = null;
			return;
		}
		int view = Math.clamp(active.player.requestedViewDistance(), 2, server.getPlayerList().getViewDistance());
		demand = new Demand(active.connection, active.player.level().dimension().identifier(),
			active.player.chunkPosition().x(), active.player.chunkPosition().z(), view);
		current.exchange.drain((connection, job) -> {
			Owner now = owner;
			Demand allowed = demand;
			if (session != current || now == null || now.connection != connection || allowed == null
				|| !allowed.includes(job.job().dimension(), job.job().chunkX(), job.job().chunkZ())) {
				throw new java.util.concurrent.CancellationException("Fixture owner demand changed");
			}
			ServerPlayNetworking.send(now.player, new SeededLeafFixturePayloads.Request(job));
			int entries = job.job().transcript().size();
			WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.sent id={} chunk={},{} entries={}",
				job.job().jobId(), job.job().chunkX(), job.job().chunkZ(), entries);
		}, (connection, claim) -> {
			Owner now = owner;
			if (now != null && now.connection == connection) {
				ServerPlayNetworking.send(now.player, new SeededLeafFixturePayloads.Cancel(claim));
			}
		});
	}

	private void disconnect() {
		demand = null;
		Owner previous = owner;
		owner = null;
		Session current = session;
		if (previous != null && current != null) { current.orchestrator.disconnect(previous.connection); }
	}
	private void stop() {
		disconnect();
		Session previous = session;
		session = null;
		if (previous != null) {
			previous.orchestrator.close();
			previous.exchange.close();
			previous.ledger.close();
			WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.stopped");
		}
	}

	public static Optional<CompletableFuture<ChunkAccess>> tryGenerate(

		WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, Supplier<CompletableFuture<ChunkAccess>> vanilla
	) {
		SeededLeafFixtureManager manager = instance;
		Session current = manager == null ? null : manager.session;
		Demand demand = manager == null ? null : manager.demand;
		if (current == null || demand == null || current.admissionStopped.get() || current.orchestrator.isBusy()
			|| !demand.includes(context.level().dimension().identifier(), chunk.getPos().x(), chunk.getPos().z())) {
			return Optional.empty();
		}
		Optional<RemoteWorldgenEligibility.EligibleContext> eligible = RemoteWorldgenEligibility.evaluate(context, step, chunks, chunk);
		if (eligible.isEmpty()) { return Optional.empty(); }
		RemoteWorldgenEligibility.EligibleContext input = eligible.orElseThrow();
		if (!input.settings().is(NoiseGeneratorSettings.OVERWORLD)) { return Optional.empty(); }
		try {
			chunk.getOrCreateNoiseChunk(candidate -> ((NoiseBasedChunkGeneratorInvoker)(Object)input.generator())
				.worldgenAssist$createNoiseChunk(candidate, input.structureManager(), input.blender(), input.level().getChunkSource().randomState()));
			Object noiseChunk = ((ChunkAccessNoiseChunkAccessor)chunk).worldgenAssist$getNoiseChunk();
			if (!(noiseChunk instanceof RemoteDensityTarget target)) { return Optional.empty(); }
			SeededLeafJobOrchestrator.Attempt attempt = current.orchestrator.submit(demand.connection,
				new SeededLeafJobOrchestrator.RecordingRequest(input.level().dimension().identifier(), chunk.getPos().x(), chunk.getPos().z(),
					input.level().getChunkSource().randomState(), input.settings(), input.noise(), 100_000), current.exchange);
			attempt.completion().thenAccept(result -> {
				// Disclosure is non-refundable. Do not repeat expensive recording after a budget/ledger rejection.
				if (result.status() == SeededLeafJobOrchestrator.Status.AUTHORIZATION_REJECTED) {
					current.admissionStopped.set(true);
				}
				WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.result chunk={},{} status={}",
					chunk.getPos().x(), chunk.getPos().z(), result.status());
			});
			RemoteDensityTarget observedTarget = new RemoteDensityTarget() {
				@Override public void worldgenAssist$installRemoteDensity(RemoteDensityField field) {
					target.worldgenAssist$installRemoteDensity(field);
					WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.applied id={} chunk={},{}",
						field.jobId(), chunk.getPos().x(), chunk.getPos().z());
				}
				@Override public void worldgenAssist$clearRemoteDensity(UUID jobId) {
					target.worldgenAssist$clearRemoteDensity(jobId);
				}
			};
			return Optional.of(SeededLeafGenerationContinuation.continueGeneration(current.orchestrator, attempt, observedTarget,
				Util.backgroundExecutor().forName("seeded_leaf_apply"), vanilla).toCompletableFuture());
		} catch (RuntimeException exception) {
			WorldgenAssist.LOGGER.warn("[CAWG] seeded_fixture.prepare_rejected reason={}", exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	/** Invoked only around source-verified main-thread synchronous chunk waits. */
	public static void duringSynchronousChunkWait(Runnable vanillaWait) {
		SeededLeafFixtureManager manager = instance;
		Session current = manager == null ? null : manager.session;
		if (current == null || !current.server.isSameThread()) { vanillaWait.run(); return; }
		boolean cancelled = current.orchestrator.suspendAdmission();
		try {
			if (cancelled) { WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.sync_fallback reason=main_thread_chunk_wait"); }
			vanillaWait.run();
		} finally {
			current.orchestrator.resumeAdmission();
		}
	}

	private record Owner(ServerPlayer player, SeededLeafJobOrchestrator.Connection connection) { }
	private record Session(MinecraftServer server, SeededLeafJobAuthority authority, SeededLeafPersistentDisclosureLedger ledger,
		SeededLeafJobOrchestrator orchestrator, SeededLeafDispatchQueue exchange,
		java.util.concurrent.atomic.AtomicBoolean admissionStopped,
		java.util.concurrent.atomic.AtomicBoolean playerCountSuspended) {
		Session(MinecraftServer server, SeededLeafJobAuthority authority, SeededLeafPersistentDisclosureLedger ledger,
			SeededLeafJobOrchestrator orchestrator, SeededLeafDispatchQueue exchange) {
			this(server, authority, ledger, orchestrator, exchange, new java.util.concurrent.atomic.AtomicBoolean(),
				new java.util.concurrent.atomic.AtomicBoolean());
		}
	}
	private record Demand(SeededLeafJobOrchestrator.Connection connection, net.minecraft.resources.Identifier dimension,
		int chunkX, int chunkZ, int viewDistance) {
		boolean includes(net.minecraft.resources.Identifier requestedDimension, int x, int z) {
			return dimension.equals(requestedDimension) && Math.abs((long)x - chunkX) <= viewDistance && Math.abs((long)z - chunkZ) <= viewDistance;
		}
	}
}
