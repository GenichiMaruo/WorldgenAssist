package io.github.genichimaruo.worldgenassist.server;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Explicit public-seed correctness fixture. Never active in performance or ordinary play. */
public final class FeatureFixture263 {
	public static final String ENVIRONMENT = "WORLDGEN_ASSIST_FEATURE_FIXTURE";
	public static final String REPLAY_ENVIRONMENT = "WORLDGEN_ASSIST_FEATURE_REPLAY";
	private static final boolean ENABLED = Boolean.getBoolean("worldgen_assist.feature_fixture")
		|| "true".equalsIgnoreCase(System.getenv(ENVIRONMENT));
	private static final Map<ServerLevel,TaskScheduler<Runnable>> SCHEDULERS = new HashMap<>();
	private static final Map<ServerLevel,FeatureFixtureQueue<ChunkAccess>> STATES = new HashMap<>();
	private static final Map<ServerLevel,Set<String>> PAUSED = new HashMap<>();
	private static final ThreadLocal<ServerLevel> CURRENT_FEATURE = new ThreadLocal<>();
	private FeatureFixture263() {}
	public static boolean enabled() {
		return ENABLED;
	}
	public static synchronized void register(ServerLevel level, TaskScheduler<Runnable> scheduler) {
		if (enabled() && level.dimension().equals(Level.OVERWORLD)) SCHEDULERS.put(level, scheduler);
	}
	public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		if (!enabled()) return;
		dispatcher.register(Commands.literal("worldgenassist_feature_fixture_start")
			.requires(source -> source.getEntity() == null && Commands.hasPermission(Commands.LEVEL_OWNERS).test(source))
			.executes(command -> arm(command.getSource().getServer())));
	}
	private static int arm(MinecraftServer server) {
		try {
			ServerLevel level = server.overworld(); TaskScheduler<Runnable> scheduler;
			if (!enabled() || !DecorationStageDigestLogger.enabled() || !Boolean.getBoolean("worldgen_assist.remote.diagnostics")
				|| level.getSeed() != 8675309L || level.getMinY() != -64 || level.getHeight() != 384
				|| level.getChunkSource().getGenerator().getClass() != NoiseBasedChunkGenerator.class)
				throw new IllegalStateException("public-seed stock Overworld diagnostic fixture required");
			synchronized (FeatureFixture263.class) {
				if (STATES.containsKey(level)) throw new IllegalStateException("fixture already armed");
				scheduler = SCHEDULERS.get(level);
			}
			if (scheduler == null) throw new IllegalStateException("original worldgen message scheduler not captured");
			var players = server.getPlayerList().getPlayers().stream().sorted(java.util.Comparator.comparing(player -> player.getScoreboardName())).toList();
			if (players.size() != 2 || !(players.get(0).getScoreboardName().equals("ScenarioOwnerA") && players.get(1).getScoreboardName().equals("ScenarioOwnerB"))
				&& !(players.get(0).getScoreboardName().equals("NativeA") && players.get(1).getScoreboardName().equals("NativeB")))
				throw new IllegalStateException("exact isolated two-owner fixture participants required");
			Set<FeatureFixtureQueue.Key> features = scope(13), spawns = scope(12);
			String file = System.getProperty("worldgen_assist.feature_replay", System.getenv(REPLAY_ENVIRONMENT));
			List<FeatureFixtureQueue.Key> order = null; String hash = "record";
			if (file != null && !file.isBlank()) {
				Path path = Path.of(file); if (!path.isAbsolute() || Files.size(path) > 131072) throw new IllegalArgumentException("bounded absolute replay required");
				byte[] bytes = Files.readAllBytes(path); if (bytes.length > 131072) throw new IllegalArgumentException("replay grew past bound");
				var json = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				if (!json.keySet().equals(Set.of("schema","seed","features")) || !json.get("schema").getAsString().equals("worldgen-assist.feature-replay.v1")
					|| json.get("seed").getAsLong() != 8675309L || json.getAsJsonArray("features").size() != features.size())
					throw new IllegalArgumentException("replay schema/seed/count");
				order = new ArrayList<>();
				for (var value : json.getAsJsonArray("features")) {
					String key = value.getAsString(); if (!key.matches("-?\\d{1,6},-?\\d{1,6}")) throw new IllegalArgumentException("replay coordinate");
					String[] parts = key.split(","); order.add(new FeatureFixtureQueue.Key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
				}
				hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			}
			String replayHash = hash;
			var state = new FeatureFixtureQueue<ChunkAccess>(features, spawns, scope(14), order, scheduler::schedule, 9,
				FeatureFixture263::delegate, key -> {
					if (!level.tickRateManager().isFrozen()) throw new IllegalStateException("fixture ticks unfrozen before feature mutation");
					WorldgenAssist.LOGGER.info("[CAWG] fixture.feature_started chunk={},{} frozen=true", key.x(), key.z());
				}, snapshot -> WorldgenAssist.LOGGER.info("[CAWG] fixture.complete replay_sha256={} snapshot={}", replayHash, snapshot));
			server.tickRateManager().setFrozen(true);
			synchronized (FeatureFixture263.class) { STATES.put(level, state); }
			// Both owner positions and eligibility precede demand. Ordinary /tp additionally calls
			// setOnGround(true), which queries supporting-block collisions and can synchronously
			// wait for FULL before the subsequent owner's creative-mode command executes.
			for (int index=0;index<2;index++) {
				var player = players.get(index); int sign = index == 0 ? 1 : -1;
				player.setGameMode(GameType.CREATIVE);
				if (!player.teleportTo(level,sign*16000.0,150.0,sign*-32000.0,Set.of(),player.getYRot(),player.getXRot(),true)
					|| player.chunkPosition().x() != sign*1000 || player.chunkPosition().z() != sign*-2000 || player.isSpectator())
					throw new IllegalStateException("fixture participant placement failed");
			}
			WorldgenAssist.LOGGER.info("[CAWG] fixture.participants ownerA={} chunkA=1000,-2000 ownerB={} chunkB=-1000,2000 creative=true before_tickets=true",
				players.get(0).getScoreboardName(),players.get(1).getScoreboardName());
			// ServerLevel.setChunkForced synchronously awaits FULL and would deadlock the snapshot barrier.
			// Insert exactly the fixture's original882 FORCED tickets before allowing the main loop to wait.
			for (var key : scope(10)) if (!level.getChunkSource().updateChunkForced(new ChunkPos(key.x(),key.z()), true))
				throw new IllegalStateException("fixture requires previously unforced chunks");
			WorldgenAssist.LOGGER.info("[CAWG] fixture.armed tickets=882 features=1458 spawns=1250 frozen={} replay_sha256={}", server.tickRateManager().isFrozen(), hash);
			return 882;
		} catch (Exception error) { WorldgenAssist.LOGGER.error("[CAWG] fixture.failed", error); throw new IllegalStateException("feature fixture arm failed", error); }
	}
	private static CompletableFuture<ChunkAccess> delegate(FeatureStageQueue.Footprint footprint, Supplier<CompletableFuture<ChunkAccess>> body) {
		return FeatureStageDispatcher.submitFixture(footprint, body);
	}
	public static Set<FeatureFixtureQueue.Key> scope(int radius) {
		if (radius < 0 || radius > 14) throw new IllegalArgumentException("fixture radius");
		var keys = new LinkedHashSet<FeatureFixtureQueue.Key>();
		for (int sign : new int[]{1,-1}) for (int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++)
			keys.add(new FeatureFixtureQueue.Key(sign*1000+x,sign*-2000+z));
		return keys;
	}
	private static synchronized FeatureFixtureQueue<ChunkAccess> state(ServerLevel level, ChunkAccess chunk, int radius) {
		if (!enabled()) return null;
		int x=chunk.getPos().x(),z=chunk.getPos().z();
		boolean inside = (Math.abs((long)x-1000)<=radius && Math.abs((long)z+2000)<=radius)
			|| (Math.abs((long)x+1000)<=radius && Math.abs((long)z-2000)<=radius);
		if (!inside || !level.dimension().equals(Level.OVERWORLD)) return null;
		var state = STATES.get(level); if (state == null) throw new IllegalStateException("fixture scope generated before explicit arm");
		return state;
	}
	public static CompletableFuture<ChunkAccess> feature(WorldGenContext context, ChunkStep step, ChunkAccess chunk,
		FeatureStageQueue.Footprint footprint, Supplier<CompletableFuture<ChunkAccess>> body) {
		var state = state(context.level(),chunk,13);
		return state == null ? null : state.feature(key(chunk),footprint,body);
	}
	public static boolean inScopedFeature() { return ENABLED && CURRENT_FEATURE.get() != null; }
	public static CompletableFuture<ChunkAccess> featureBody(WorldGenContext context, ChunkAccess chunk, Supplier<CompletableFuture<ChunkAccess>> body) {
		if (!ENABLED || state(context.level(),chunk,13) == null) return body.get();
		if (CURRENT_FEATURE.get() != null) throw new IllegalStateException("nested fixture feature body");
		CURRENT_FEATURE.set(context.level());
		try { return body.get(); } finally { CURRENT_FEATURE.remove(); }
	}
	public static CompletableFuture<ChunkAccess> terrain(WorldGenContext context, ChunkAccess chunk, Supplier<CompletableFuture<ChunkAccess>> body) {
		var state = state(context.level(),chunk,14);
		return state == null ? body.get() : state.terrain(key(chunk),body);
	}
	public static CompletableFuture<ChunkAccess> initialization(WorldGenContext context, ChunkAccess chunk,
		FeatureStageQueue.Footprint footprint, Supplier<CompletableFuture<ChunkAccess>> body) {
		var state = state(context.level(),chunk,13);
		return state == null ? null : state.initialization(key(chunk),footprint,body);
	}
	public static CompletableFuture<ChunkAccess> spawn(WorldGenContext context, ChunkAccess chunk, Supplier<CompletableFuture<ChunkAccess>> body) {
		var state = state(context.level(),chunk,12); if (state == null) return body.get();
		return state.spawn(key(chunk), () -> {
			if (!context.level().tickRateManager().isFrozen()) throw new IllegalStateException("fixture ticks unfrozen at snapshot");
			DecorationStageDigestLogger.log(context,chunk);
		}, body);
	}
	public static boolean captured(WorldGenContext context, ChunkAccess chunk) {
		var state = state(context.level(),chunk,12); return state != null && state.snapshotCaptured(key(chunk));
	}
	private static FeatureFixtureQueue.Key key(ChunkAccess chunk) { return new FeatureFixtureQueue.Key(chunk.getPos().x(),chunk.getPos().z()); }
	public static boolean suspendPlayer(net.minecraft.server.level.ServerPlayer player) {
		if (!ENABLED) return false;
		String name = player.getScoreboardName();
		if (!(name.equals("ScenarioOwnerA") || name.equals("ScenarioOwnerB") || name.equals("NativeA") || name.equals("NativeB"))) return false;
		synchronized (FeatureFixture263.class) {
			var state = STATES.get(player.level());
			if (state == null || !state.awaitingSpawnCompletion()) return false;
			if (PAUSED.computeIfAbsent(player.level(),level -> new java.util.HashSet<>()).add(name))
				WorldgenAssist.LOGGER.info("[CAWG] fixture.player_paused owner={} connection_active=true",name);
			return true;
		}
	}
	public static synchronized void stopped(MinecraftServer server) {
		for (var iterator = STATES.entrySet().iterator();iterator.hasNext();) {
			var entry = iterator.next(); if (entry.getKey().getServer() != server) continue;
			entry.getValue().close(); var snapshot = entry.getValue().snapshot();
			WorldgenAssist.LOGGER.info("[CAWG] fixture.stopped snapshot={}",snapshot);
			if (snapshot.failed()) WorldgenAssist.LOGGER.error("[CAWG] fixture.failed incomplete or rejected workload");
			iterator.remove();
		}
		SCHEDULERS.keySet().removeIf(level -> level.getServer() == server);
		PAUSED.keySet().removeIf(level -> level.getServer() == server);
	}
}
