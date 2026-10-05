package io.github.genichimaruo.worldgenassist.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Replaces the existing consecutive worldgen message queue, adding asynchronous backpressure. */
public final class FeatureStageDispatcher implements TaskScheduler<Runnable> {
	public static final int CAPACITY = 128;
	// A stock runUntilWait can admit radius1 FEATURES and INITIALIZE_LIGHT layers.
	public static final int MESSAGE_FEATURE_RESERVATION = 18;
	private static final ThreadLocal<FeatureStageDispatcher> CURRENT = new ThreadLocal<>();
	private final FeatureStageQueue features;
	private final ServerLevel level;
	private final FeatureStageConfig config;
	private final FeatureMessagePump messages;
	private int admittedInMessage;

	public FeatureStageDispatcher(ServerLevel level, Executor executor, FeatureStageConfig config) {
		if (config == FeatureStageConfig.OFF) throw new IllegalArgumentException("disabled feature dispatcher");
		this.level = level;
		this.config = config;
		features = new FeatureStageQueue(config.workers(), CAPACITY, "CAWG-Features-" + level.dimension().identifier() + "-");
		messages = new FeatureMessagePump(executor, features, MESSAGE_FEATURE_RESERVATION, this::runMessage);
		WorldgenAssist.LOGGER.info("[CAWG] feature.backend mode={} workers={} capacity={} message_reservation={} dimension={}",
			config, config.workers(), CAPACITY, MESSAGE_FEATURE_RESERVATION, level.dimension().identifier());
	}

	@Override public String name() { return "worldgen"; }
	@Override public Runnable wrapRunnable(Runnable task) { return task; }
	@Override public void schedule(Runnable task) { messages.schedule(task); }
	private void runMessage(Runnable task) {
		if (CURRENT.get() != null) throw new IllegalStateException("nested worldgen message");
		CURRENT.set(this);
		admittedInMessage = 0;
		try { task.run(); }
		finally {
			CURRENT.remove();
		}
	}

	public static CompletableFuture<ChunkAccess> generate(WorldGenContext context, ChunkStep step, ChunkAccess chunk,
		Supplier<CompletableFuture<ChunkAccess>> original) {
		if (FeatureFixture263.enabled()) {
			var footprint = footprint(context,step,chunk,FeatureStageConfig.current());
			long admitted = System.nanoTime();
			var fixture = FeatureFixture263.feature(context,step,chunk,footprint,() -> runBody(context,chunk,original,admitted));
			if (fixture != null) return fixture;
		}
		if (FeatureStageConfig.current() == FeatureStageConfig.OFF
			|| context.generator().getClass() != NoiseBasedChunkGenerator.class
			|| !context.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD))
			return runBody(context, chunk, original, System.nanoTime());
		if (CURRENT.get() == null || CURRENT.get().level != context.level())
			throw new IllegalStateException("experimental FEATURES called outside guarded stock worldgen dispatcher");
		long admitted = System.nanoTime();
		return submitFixture(footprint(context,step,chunk,FeatureStageConfig.current()),
			() -> runBody(context,chunk,original,admitted));
	}
	static CompletableFuture<ChunkAccess> submitFixture(FeatureStageQueue.Footprint footprint, Supplier<CompletableFuture<ChunkAccess>> body) {
		if (FeatureStageConfig.current() == FeatureStageConfig.OFF) return body.get();
		FeatureStageDispatcher dispatcher = CURRENT.get();
		if (dispatcher == null)
			throw new IllegalStateException("experimental FEATURES called outside guarded stock worldgen dispatcher");
		if (++dispatcher.admittedInMessage > MESSAGE_FEATURE_RESERVATION)
			throw new IllegalStateException("stock FEATURES/INITIALIZE_LIGHT message reservation exceeded before mutation");
		return dispatcher.features.submitAsync(footprint, body);
	}

	public static CompletableFuture<ChunkAccess> initializeLight(WorldGenContext context, ChunkStep step, ChunkAccess chunk,
		Supplier<CompletableFuture<ChunkAccess>> original) {
		long admitted = System.nanoTime();
		Supplier<CompletableFuture<ChunkAccess>> body = () -> {
			long started = System.nanoTime();
			CompletableFuture<ChunkAccess> result = original.get();
			long invoked = System.nanoTime();
			if (!Boolean.getBoolean("worldgen_assist.remote.diagnostics")) return result;
			String thread = Thread.currentThread().getName();
			return result.whenComplete((value, error) -> {
				long ended = System.nanoTime();
				WorldgenAssist.LOGGER.info("[CAWG] light_init.executed chunk={},{} dimension={} queue_ms={} compute_ms={} owned_ms={} thread={} start_ns={} end_ns={} failed={}",
					chunk.getPos().x(), chunk.getPos().z(), context.level().dimension().identifier(),
					(started-admitted)/1_000_000.0, (invoked-started)/1_000_000.0, (ended-started)/1_000_000.0, thread, started, ended, error != null);
			});
		};
		FeatureStageConfig config = FeatureStageConfig.current();
		var footprint = config == FeatureStageConfig.PARALLEL && step.targetStatus() == ChunkStatus.INITIALIZE_LIGHT
			&& step.directDependencies().getRadius() == 0 && step.blockStateWriteRadius() == -1
			? FeatureStageQueue.Footprint.region(chunk.getPos().x(), chunk.getPos().z(), 0)
			: FeatureStageQueue.Footprint.serial();
		if (FeatureFixture263.enabled()) {
			var fixture = FeatureFixture263.initialization(context,chunk,footprint,body);
			if (fixture != null) return fixture;
		}
		if (config == FeatureStageConfig.OFF || context.generator().getClass() != NoiseBasedChunkGenerator.class
			|| !context.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return body.get();
		if (CURRENT.get() == null || CURRENT.get().level != context.level())
			throw new IllegalStateException("experimental INITIALIZE_LIGHT called outside guarded stock worldgen dispatcher");
		// Source scan, light-engine attachment and its original async initialization retain the
		// same ownership as neighboring FEATURES. Never retry or wait on an owned worker.
		return submitFixture(footprint, body);
	}
	private static FeatureStageQueue.Footprint footprint(WorldGenContext context, ChunkStep step, ChunkAccess chunk, FeatureStageConfig config) {
		if (config == FeatureStageConfig.PARALLEL && context.generator().getClass() == NoiseBasedChunkGenerator.class
			&& context.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
			&& step.targetStatus() == ChunkStatus.FEATURES && step.blockStateWriteRadius() == 1
			&& step.directDependencies().getRadius() == 8) {
			// Reserve every region-readable holder, including shared starts/pieces, not just block writes.
			return FeatureStageQueue.Footprint.region(chunk.getPos().x(), chunk.getPos().z(), 8);
		}
		return FeatureStageQueue.Footprint.serial();
	}

	private static CompletableFuture<ChunkAccess> runBody(WorldGenContext context, ChunkAccess chunk,
		Supplier<CompletableFuture<ChunkAccess>> original, long admitted) {
		long started = System.nanoTime();
		try {
			return FeatureFixture263.featureBody(context,chunk,original);
		} finally {
			long ended = System.nanoTime();
			if (Boolean.getBoolean("worldgen_assist.remote.diagnostics")) WorldgenAssist.LOGGER.info(
				"[CAWG] feature.executed chunk={},{} dimension={} queue_ms={} compute_ms={} thread={} start_ns={} end_ns={}",
				chunk.getPos().x(), chunk.getPos().z(), context.level().dimension().identifier(),
				(started-admitted)/1_000_000.0, (ended-started)/1_000_000.0, Thread.currentThread().getName(), started, ended);
		}
	}

	@Override public void close() {
		messages.close();
		WorldgenAssist.LOGGER.info("[CAWG] feature.shutdown dimension={} pauses={} remaining_messages={} snapshot={}",
			level.dimension().identifier(), messages.pauses(), messages.queued(), features.snapshot());
	}
}
