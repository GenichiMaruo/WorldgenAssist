package io.github.genichimaruo.worldgenassist.client;

import java.util.Objects;

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

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.server.SeededLeafTrace;

/** Seed-free client calculation using only a public dummy seed plus a bounded transcript. */
final class SeededLeafClientDensityComputer {
	static final long PUBLIC_DUMMY_SEED = -7046029254386353131L;

	private SeededLeafClientDensityComputer() {
	}

	static SeededLeafDensityResult compute(
		HolderLookup.Provider worldgenRegistries,
		Identifier currentDimension,
		AuthorizedSeededLeafJob authorization
	) {
		Objects.requireNonNull(worldgenRegistries, "worldgenRegistries");
		Objects.requireNonNull(currentDimension, "currentDimension");
		Objects.requireNonNull(authorization, "authorization");
		SeededLeafJob job = authorization.job();
		if (!Level.OVERWORLD.identifier().equals(job.dimension()) || !currentDimension.equals(job.dimension())) {
			throw new RejectedJobException("unsupported_dimension");
		}

		ResourceKey<NoiseGeneratorSettings> settingsKey = ResourceKey.create(Registries.NOISE_SETTINGS, job.noiseSettings());
		Holder.Reference<NoiseGeneratorSettings> settings = worldgenRegistries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.get(settingsKey)
			.orElseThrow(() -> new RejectedJobException("unsupported_noise_settings"));
		NoiseSettings configuredNoise = settings.value().noiseSettings();
		if (configuredNoise.getCellWidth() != job.cellWidth()
			|| configuredNoise.getCellHeight() != job.cellHeight()
			|| job.minY() < configuredNoise.minY()
			|| Math.addExact(job.minY(), job.height())
				> Math.addExact(configuredNoise.minY(), configuredNoise.height())) {
			throw new RejectedJobException("geometry_mismatch");
		}
		NoiseSettings noiseSettings = new NoiseSettings(
			job.minY(),
			job.height(),
			configuredNoise.noiseSizeHorizontal(),
			configuredNoise.noiseSizeVertical()
		);

		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = worldgenRegistries.lookupOrThrow(Registries.NOISE);
		RandomState replayState = RandomState.create(settings.value(), noises, PUBLIC_DUMMY_SEED);
		SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(authorization);
		SeededLeafTrace.Replay replay = SeededLeafTrace.beginReplay(replayState.router(), job.transcript());
		try (replay) {
			ClientDensitySampler.Sample sample = new ClientDensitySampler(
				job.chunkX(),
				job.chunkZ(),
				job.minY(),
				job.height(),
				job.cellWidth(),
				job.cellHeight(),
				replayState,
				settings.value(),
				noiseSettings
			).sample();
			return new SeededLeafDensityResult(claim, sample.densities(), sample.computeNanos());
		} catch (SeededLeafTrace.TraceMismatchException exception) {
			throw new RejectedJobException("transcript_mismatch", exception);
		}
	}

	static final class RejectedJobException extends RuntimeException {
		private final String reason;

		private RejectedJobException(String reason) {
			this(reason, null);
		}

		private RejectedJobException(String reason, Throwable cause) {
			super("Client rejected seeded-leaf job: " + reason, cause);
			this.reason = reason;
		}

		String reason() {
			return reason;
		}
	}
}
