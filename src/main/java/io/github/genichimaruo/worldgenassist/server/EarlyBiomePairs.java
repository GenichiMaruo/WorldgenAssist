package io.github.genichimaruo.worldgenassist.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import io.github.genichimaruo.worldgenassist.common.CompleteBiomeData;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.network.TerrainBiomeResultPayload;

/** At most the existing pending-job bound. Only manager-selected NONAUDIT,
 * already-trusted distinct owners may form a pair; unknown packets are discarded. */
final class EarlyBiomePairs<K> {
	private final int maxJobs;
	private final Map<UUID, Pair> jobs = new HashMap<>();
	private final Map<K, Pair> keys = new HashMap<>();
	EarlyBiomePairs(int maxJobs) { if (maxJobs < 2) throw new IllegalArgumentException("Pair bound"); this.maxJobs = maxJobs; }
	synchronized Pair register(K key, RemoteJobCoordinator.Submission a, RemoteJobCoordinator.Submission b,
		long deadline, BooleanSupplier current) {
		prune();
		if (jobs.size() + 2 > maxJobs || keys.containsKey(key) || jobs.containsKey(a.job().identity().jobId())
			|| jobs.containsKey(b.job().identity().jobId())) return null;
		Pair pair = new Pair(a, b, deadline, current);
		keys.put(key, pair); jobs.put(a.job().identity().jobId(), pair); jobs.put(b.job().identity().jobId(), pair);
		return pair;
	}
	synchronized Pair receive(UUID owner, TerrainBiomeResultPayload payload) {
		return receive(owner, payload, () -> true);
	}
	synchronized Pair receive(UUID owner, TerrainBiomeResultPayload payload, BooleanSupplier connection) {
		Pair pair = jobs.get(payload.identity().jobId());
		if (pair == null || !pair.current()) return null;
		pair.accept(owner, payload, connection); return pair;
	}
	synchronized Optional<Pair> get(K key) {
		Pair pair = keys.get(key);
		return pair != null && pair.current() ? Optional.of(pair) : Optional.empty();
	}
	synchronized void remove(Pair pair) {
		if (pair == null) return;
		pair.revoke(); jobs.values().removeIf(value -> value == pair); keys.values().removeIf(value -> value == pair);
	}
	synchronized void complete(Pair pair, boolean verified) {
		if (pair == null) return;
		pair.finish(verified); jobs.values().removeIf(value -> value == pair); keys.values().removeIf(value -> value == pair);
	}
	synchronized void removeOwner(UUID owner) {
		keys.values().stream().filter(pair -> pair.a.ownerId().equals(owner) || pair.b.ownerId().equals(owner))
			.distinct().toList().forEach(this::remove);
	}
	synchronized void clear() { keys.values().forEach(Pair::revoke); jobs.clear(); keys.clear(); }
	synchronized int size() { prune(); return jobs.size(); }
	private void prune() { keys.values().stream().filter(pair -> !pair.current()).distinct().toList().forEach(this::remove); }

	static final class Pair {
		private final RemoteJobCoordinator.Submission a, b;
		private final long deadline;
		private final BooleanSupplier authority;
		private volatile boolean active = true;
		private volatile boolean completedVerified;
		private volatile BooleanSupplier firstConnection = () -> true, secondConnection = () -> true;
		private CompleteBiomeData first, second, fullFirst, fullSecond;
		Pair(RemoteJobCoordinator.Submission a, RemoteJobCoordinator.Submission b, long deadline, BooleanSupplier authority) {
			TerrainDensityJob x = a.job(), y = b.job();
			if (a.ownerId().equals(b.ownerId()) || x.identity().jobId().equals(y.identity().jobId()) || !x.earlyBiomes() || !y.earlyBiomes()
				|| !x.identity().protocolVersion().equals(y.identity().protocolVersion()) || !x.identity().dimension().equals(y.identity().dimension())
				|| !x.identity().chunkPos().equals(y.identity().chunkPos()) || !x.identity().contextFingerprint().equals(y.identity().contextFingerprint())
				|| x.worldSeed() != y.worldSeed() || x.generateStructures() != y.generateStructures() || !x.shaping().equals(y.shaping())
				|| !x.noiseSettings().equals(y.noiseSettings()) || x.minY() != y.minY() || x.height() != y.height()
				|| x.workKind() != y.workKind() || x.cellWidth() != y.cellWidth() || x.cellHeight() != y.cellHeight()) {
				throw new IllegalArgumentException("Invalid early biome pair");
			}
			this.a = a; this.b = b; this.deadline = deadline; this.authority = java.util.Objects.requireNonNull(authority);
		}
		boolean current() { return (active || completedVerified) && System.nanoTime() - deadline < 0 && authority.getAsBoolean()
			&& firstConnection.getAsBoolean() && secondConnection.getAsBoolean(); }
		synchronized void revoke() { active = false; completedVerified = false; }
		synchronized void finish(boolean verified) {
			// Only already captured first-phase approval survives successful final verification.
			// No map/cache entry or TTL is extended; failures invalidate it immediately.
			completedVerified = verified && first != null && first.equals(second) && first.equals(fullFirst) && second.equals(fullSecond);
			active = false;
		}
		void requireCurrent() { if (!current()) throw new CancellationException("Early biome authority expired"); }
		synchronized void accept(UUID owner, TerrainBiomeResultPayload payload, BooleanSupplier connection) {
			requireCurrent();
			boolean primary = payload.identity().equals(a.job().identity()) && owner.equals(a.ownerId());
			boolean peer = payload.identity().equals(b.job().identity()) && owner.equals(b.ownerId());
			if (!primary && !peer) return; // wrong owner/identity cannot contribute to agreement
			CompleteBiomeData data = payload.biomes();
			if (data.minY() != a.job().minY() || data.height() != a.job().height()) throw mismatch("Early biome geometry differs");
			CompleteBiomeData prior = primary ? first : second, full = primary ? fullFirst : fullSecond;
			if (prior != null && !prior.equals(data) || full != null && !full.equals(data)) throw mismatch("Changed early biome reply");
			if (!connection.getAsBoolean()) return;
			if (primary) { if (first == null) firstConnection = java.util.Objects.requireNonNull(connection); first = data; }
			else { if (second == null) secondConnection = java.util.Objects.requireNonNull(connection); second = data; }
		}
		synchronized Optional<CompleteBiomeData> ready() {
			// A history-dependent difference is NOT normalized or treated as authority.
			return current() && first != null && first.equals(second) ? Optional.of(first) : Optional.empty();
		}
		synchronized TerrainDensityResult checkFull(TerrainDensityResult result) {
			requireCurrent();
			boolean primary = result.identity().equals(a.job().identity()), peer = result.identity().equals(b.job().identity());
			if (!primary && !peer) throw mismatch("Full biome assignment differs");
			CompleteBiomeData data = result.completeTerrain().centerBiomes(), early = primary ? first : second;
			if (data == null || early != null && !early.equals(data)) throw mismatch("Early and final center biomes differ");
			if (primary) fullFirst = data; else fullSecond = data;
			return result;
		}
		UUID primaryOwner() { return a.ownerId(); }
		UUID peerOwner() { return b.ownerId(); }
		UUID primaryId() { return a.job().identity().jobId(); }
		UUID peerId() { return b.job().identity().jobId(); }
		private RemoteDensityValidator.RemoteDensityValidationException mismatch(String reason) {
			active = false; completedVerified = false; return new RemoteDensityValidator.RemoteDensityValidationException(reason);
		}
	}
}
