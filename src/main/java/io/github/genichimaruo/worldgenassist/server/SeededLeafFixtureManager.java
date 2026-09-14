package io.github.genichimaruo.worldgenassist.server;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
	private static final int MAX_ATTEMPTS = 8;
	private static volatile SeededLeafFixtureManager instance;
	private volatile Session session;
	// Player objects are used only on the server thread; generation reads immutable demand snapshots.
	private final Map<UUID, Owner> owners = new HashMap<>();
	private volatile List<Demand> demands = List.of();
	private SeededLeafFixtureManager() { }

	public static void register() {
		if (!SeededLeafFixtureConfig.serverEnabled()) { return; }
		if (instance != null) { throw new IllegalStateException("Fixture manager already registered"); }
		SeededLeafFixtureManager manager = new SeededLeafFixtureManager();
		instance = manager;
		ServerLifecycleEvents.SERVER_STARTING.register(manager::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> manager.stop());
		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> {
			manager.demands = List.of();
			Session current = manager.session;
			if (current != null) {
				current.orchestrator.reload();
				WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.reload generation={}", current.authority.contextGeneration());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(manager::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
			// Fabric can notify from Netty's channel-close callback. Owner maps
			// and player snapshots belong to the server thread.
			server.execute(() -> manager.disconnect(listener.player));
		});
		ServerPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Hello.TYPE, (payload, context) -> {
			boolean accepted = manager.accept(context.player(), payload.protocol());
			context.responseSender().sendPacket(new SeededLeafFixturePayloads.Accepted(accepted));
			WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.handshake accepted={} owner={} player={}",
				accepted, context.player().getUUID(), context.player().getGameProfile().name());
		});
		ServerPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Result.TYPE, (payload, context) -> {
			Session current = manager.session;
			Owner active = manager.owners.get(context.player().getUUID());
			if (current != null && active != null && active.player == context.player()) {
				current.exchange.receive(active.connection, payload.result());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Failure.TYPE, (payload, context) -> {
			Session current = manager.session;
			Owner active = manager.owners.get(context.player().getUUID());
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
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(MAX_ATTEMPTS, 1, 100_000, 100_000,
			TIMEOUT, Duration.ofSeconds(30), System::nanoTime, UUID::randomUUID,
			() -> OpaqueWorldgenContextId.random(random), () -> SeededLeafJobAuthenticator.random(random), ledger);
		authority.start();
		SeededLeafJobOrchestrator orchestrator = new SeededLeafJobOrchestrator(authority, 64, TIMEOUT, MAX_ATTEMPTS);
		session = new Session(server, authority, ledger, orchestrator, new SeededLeafDispatchQueue(orchestrator, MAX_ATTEMPTS));
		WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.started protocol=3 policy=public_fixture_only");
	}

	private boolean accept(ServerPlayer player, int protocol) {
		Session current = session;
		if (current == null || protocol != SeededLeafFixtureConfig.PROTOCOL
			|| !ServerPlayNetworking.canSend(player, SeededLeafFixturePayloads.Request.TYPE)
			|| !ServerPlayNetworking.canSend(player, SeededLeafFixturePayloads.Cancel.TYPE)) { return false; }
		Owner active = owners.get(player.getUUID());
		if (active != null) { return active.player == player; }
		if (owners.size() >= 64) { return false; }
		owners.put(player.getUUID(), new Owner(player, current.orchestrator.connect(player.getUUID())));
		return true;
	}

	private void tick(MinecraftServer server) {
		Session current = session;
		if (current == null || current.server != server) { return; }
		demands = owners.values().stream().filter(active -> !active.player.isChangingDimension() && !active.player.isSpectator()).map(active -> {
			int view = Math.clamp(active.player.requestedViewDistance(), 2, server.getPlayerList().getViewDistance());
			return new Demand(active.connection, new PlayerChunkDemand(active.connection.ownerId(),
				active.player.level().dimension().identifier(), active.player.chunkPosition().x(), active.player.chunkPosition().z(), view));
		}).toList();
		current.exchange.drain((connection, job) -> {
			Owner now = owners.get(connection.ownerId());
			Demand allowed = demands.stream().filter(candidate -> candidate.connection == connection).findFirst().orElse(null);
			if (session != current || now == null || now.connection != connection || allowed == null
				|| !allowed.view.includes(job.job().dimension(), job.job().chunkX(), job.job().chunkZ())) {
				throw new java.util.concurrent.CancellationException("Fixture owner demand changed");
			}
			ServerPlayNetworking.send(now.player, new SeededLeafFixturePayloads.Request(job));
			int entries = job.job().transcript().size();
			WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.sent id={} chunk={},{} entries={} owner={}",
				job.job().jobId(), job.job().chunkX(), job.job().chunkZ(), entries, connection.ownerId());
		}, (connection, claim) -> {
			Owner now = owners.get(connection.ownerId());
			if (now != null && now.connection == connection) {
				ServerPlayNetworking.send(now.player, new SeededLeafFixturePayloads.Cancel(claim));
			}
		});
	}

	private void disconnect(ServerPlayer player) {
		Owner previous = owners.get(player.getUUID());
		if (previous == null || previous.player != player) { return; }
		owners.remove(player.getUUID());
		demands = demands.stream().filter(demand -> demand.connection != previous.connection).toList();
		Session current = session;
		if (current != null) { current.orchestrator.disconnect(previous.connection); }
		WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.disconnect owner={} remaining_workers={}", player.getUUID(), owners.size());
	}
	private void stop() {
		demands = List.of();
		owners.clear();
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
		List<Demand> snapshot = manager == null ? List.of() : manager.demands;
		PlayerChunkDemand selected = PlayerChunkDemand.select(snapshot.stream().map(Demand::view).toList(),
			context.level().dimension().identifier(), chunk.getPos().x(), chunk.getPos().z()).orElse(null);
		Demand demand = selected == null ? null : snapshot.stream().filter(candidate -> candidate.view == selected).findFirst().orElse(null);
		if (current == null || demand == null || current.admissionStopped.get() || current.orchestrator.isBusy(demand.connection)) {
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
			if (!attempt.completion().toCompletableFuture().isDone()) {
				WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.admitted chunk={},{} owner={} active={}",
					chunk.getPos().x(), chunk.getPos().z(), demand.connection.ownerId(), current.orchestrator.pendingCount());
			}
			attempt.completion().thenAccept(result -> {
				// Disclosure is non-refundable. Do not repeat expensive recording after a budget/ledger rejection.
				if (result.status() == SeededLeafJobOrchestrator.Status.AUTHORIZATION_REJECTED) {
					current.admissionStopped.set(true);
				}
				WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.result chunk={},{} status={} owner={}",
					chunk.getPos().x(), chunk.getPos().z(), result.status(), demand.connection.ownerId());
			});
			RemoteDensityTarget observedTarget = new RemoteDensityTarget() {
				@Override public void worldgenAssist$installRemoteDensity(RemoteDensityField field) {
					target.worldgenAssist$installRemoteDensity(field);
					WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.applied id={} chunk={},{} owner={}",
						field.jobId(), chunk.getPos().x(), chunk.getPos().z(), demand.connection.ownerId());
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
		java.util.concurrent.atomic.AtomicBoolean admissionStopped) {
		Session(MinecraftServer server, SeededLeafJobAuthority authority, SeededLeafPersistentDisclosureLedger ledger,
			SeededLeafJobOrchestrator orchestrator, SeededLeafDispatchQueue exchange) {
			this(server, authority, ledger, orchestrator, exchange, new java.util.concurrent.atomic.AtomicBoolean());
		}
	}
	private record Demand(SeededLeafJobOrchestrator.Connection connection, PlayerChunkDemand view) { }
}
