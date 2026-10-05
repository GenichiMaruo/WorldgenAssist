package io.github.genichimaruo.worldgenassist.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainWorkKind;
import io.github.genichimaruo.worldgenassist.common.SurfaceDensityData;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.server.WorldgenContextFingerprintFactory;
import io.github.genichimaruo.worldgenassist.WorldgenAssist;

final class ClientTerrainDensityComputer {
	private ClientTerrainDensityComputer() {
	}

	static TerrainDensityResult compute(HolderLookup.Provider worldgenRegistries, Identifier currentDimension, TerrainDensityJob job) {
		return compute(new Session(worldgenRegistries), currentDimension, job);
	}

	static TerrainDensityResult compute(Session session, Identifier currentDimension, TerrainDensityJob job) {
		long prepareStarted = System.nanoTime();
		if (!currentDimension.equals(job.identity().dimension())) {
			throw new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
		}

		ResourceKey<NoiseGeneratorSettings> settingsKey = ResourceKey.create(Registries.NOISE_SETTINGS, job.noiseSettings());
		Holder.Reference<NoiseGeneratorSettings> settings = session.registries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.get(settingsKey)
			.orElseThrow(() -> new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT));
		NoiseSettings noiseSettings = settings.value().noiseSettings();
		if (noiseSettings.minY() != job.minY()
			|| noiseSettings.height() != job.height()
			|| job.cellWidth() != 1 || job.cellHeight() != 1) {
			throw new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
		}

		Prepared prepared = session.prepare(job, settings);
		if (!prepared.fingerprint().equals(job.identity().contextFingerprint())) {
			throw new RejectedJobException(TerrainJobFailurePayload.Reason.CONTEXT_MISMATCH);
		}

		WorldgenAssist.LOGGER.info("[CAWG] job.client_prepared id={} preparation_ms={} contexts={}",
			job.identity().jobId(), (System.nanoTime() - prepareStarted) / 1_000_000.0, session.size());
		RandomState randomState = prepared.state();
		if (job.workKind() == TerrainWorkKind.COMPLETE_TERRAIN) {
			long started = System.nanoTime();
			var terrain = prepared.terrainComputer().compute(job, prepared.generator(), randomState, session.probe);
			return TerrainDensityResult.fromCompleteTerrain(job.identity(), terrain, System.nanoTime() - started);
		}
		if (job.workKind() == TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE) {
			if (!io.github.genichimaruo.worldgenassist.common.TerrainDecisionData.supports(job.noiseSettings(),settings.value())) {
				throw new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
			}
			return io.github.genichimaruo.worldgenassist.common.TerrainDecisionData.sample(job,randomState,settings.value());
		}
		if (job.workKind() == TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE) {
			if (!SurfaceDensityData.supports(job.noiseSettings(), settings.value())) {
				throw new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
			}
			return io.github.genichimaruo.worldgenassist.common.BlockDensityData.sample(job, randomState, settings.value());
		}
		if (job.workKind() == TerrainWorkKind.GRID_AND_SURFACE) {
			if (!SurfaceDensityData.supports(job.noiseSettings(), settings.value()) || !io.github.genichimaruo.worldgenassist.common.GridDensityData.supports(settings.value())) {
				throw new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
			}
			return io.github.genichimaruo.worldgenassist.common.GridDensityData.sample(job, randomState, settings.value());
		}
		if (job.workKind() == TerrainWorkKind.SURFACE_FIELDS) {
			if (!SurfaceDensityData.supports(job.noiseSettings(), settings.value())) {
				throw new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
			}
			return SurfaceDensityData.sample(job, randomState, settings.value());
		}
		ClientDensitySampler.Sample sample = new ClientDensitySampler(
			job.identity().chunkX(),
			job.identity().chunkZ(),
			job.minY(),
			job.height(),
			job.cellWidth(),
			job.cellHeight(),
			randomState,
			settings.value(),
			noiseSettings
		).sample();
		return new TerrainDensityResult(job.identity(), sample.densities(), sample.computeNanos());
	}

	/** Each worker owns its sampler compiler; at most three contexts per worker. */
	static final class Session {
		private final io.github.genichimaruo.worldgenassist.common.PublicPeerProbe probe = new io.github.genichimaruo.worldgenassist.common.PublicPeerProbe(
			Boolean.getBoolean("worldgen_assist.client.peer_probe"),java.nio.file.Path.of("worldgen-assist-peer-probe"));
		private final HolderLookup.Provider registries;
		private final Map<Thread, Map<ContextKey, Prepared>> workers = new ConcurrentHashMap<>();
		private final boolean reuse = !"false".equalsIgnoreCase(System.getProperty("worldgen_assist.client.reuse_context",
			System.getenv("WORLDGEN_ASSIST_CLIENT_REUSE_CONTEXT")));

		Session(HolderLookup.Provider registries) { this.registries = registries; }

		Prepared prepare(TerrainDensityJob job, Holder.Reference<NoiseGeneratorSettings> settings) {
			Map<ContextKey, Prepared> cache = workers.computeIfAbsent(Thread.currentThread(),
				ignored -> new LinkedHashMap<>(3, 0.75F, true));
			ContextKey key = new ContextKey(job.identity().dimension(), job.worldSeed(), job.generateStructures(),
				job.noiseSettings(), job.minY(), job.height(), job.workKind());
			synchronized (cache) {
				Prepared existing = cache.get(key);
				if (reuse && existing != null) return existing;
				boolean complete = key.kind() == TerrainWorkKind.COMPLETE_TERRAIN;
				var generator = complete ? new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
					net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(
						Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(
						net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD)), settings) : null;
				var fingerprint = complete ? WorldgenContextFingerprintFactory.createCompleteTerrain(registries,
					key.dimension(), key.seed(), key.structures(), key.minY(), key.height(), settings, generator.getBiomeSource())
					: WorldgenContextFingerprintFactory.create(registries, key.dimension(), key.seed(), key.structures(), key.minY(), key.height(), settings);
				Prepared created = new Prepared(fingerprint,
					RandomState.create(registries.lookupOrThrow(Registries.NOISE), key.seed(), settings.value()), generator,
					complete ? new io.github.genichimaruo.worldgenassist.common.PrivateTerrainComputer(registries) : null);
				cache.put(key, created);
				if (cache.size() > 3) cache.remove(cache.keySet().iterator().next());
				return created;
			}
		}

		void clear() { workers.clear(); }
		int size() {
			return workers.values().stream().mapToInt(cache -> { synchronized (cache) { return cache.size(); } }).sum();
		}
	}

	private record ContextKey(Identifier dimension, long seed, boolean structures, Identifier settings, int minY, int height, TerrainWorkKind kind) { }
	private record Prepared(WorldgenContextFingerprint fingerprint, RandomState state,
		net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator,
		io.github.genichimaruo.worldgenassist.common.PrivateTerrainComputer terrainComputer) { }

	static final class RejectedJobException extends RuntimeException {
		private final TerrainJobFailurePayload.Reason reason;

		private RejectedJobException(TerrainJobFailurePayload.Reason reason) {
			super("Client rejected remote terrain job: " + reason);
			this.reason = reason;
		}

		TerrainJobFailurePayload.Reason reason() {
			return reason;
		}
	}
}
