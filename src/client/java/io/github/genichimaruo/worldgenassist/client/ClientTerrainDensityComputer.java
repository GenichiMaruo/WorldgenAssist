package io.github.genichimaruo.worldgenassist.client;

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
import io.github.genichimaruo.worldgenassist.common.SupportedDimensions;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.server.WorldgenContextFingerprintFactory;

final class ClientTerrainDensityComputer {
	private ClientTerrainDensityComputer() {
	}

	static TerrainDensityResult compute(HolderLookup.Provider worldgenRegistries, Identifier currentDimension, TerrainDensityJob job) {
		if (!SupportedDimensions.contains(job.identity().dimension()) || !currentDimension.equals(job.identity().dimension())) {
			throw new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
		}

		ResourceKey<NoiseGeneratorSettings> settingsKey = ResourceKey.create(Registries.NOISE_SETTINGS, job.noiseSettings());
		Holder.Reference<NoiseGeneratorSettings> settings = worldgenRegistries
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.get(settingsKey)
			.orElseThrow(() -> new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT));
		NoiseSettings noiseSettings = settings.value().noiseSettings();
		if (noiseSettings.minY() != job.minY()
			|| noiseSettings.height() != job.height()
			|| noiseSettings.getCellWidth() != job.cellWidth()
			|| noiseSettings.getCellHeight() != job.cellHeight()) {
			throw new RejectedJobException(TerrainJobFailurePayload.Reason.UNSUPPORTED_CONTEXT);
		}

		WorldgenContextFingerprint fingerprint = WorldgenContextFingerprintFactory.create(
			worldgenRegistries,
			job.identity().dimension(),
			job.worldSeed(),
			job.generateStructures(),
			job.minY(),
			job.height(),
			settings
		);
		if (!fingerprint.equals(job.identity().contextFingerprint())) {
			throw new RejectedJobException(TerrainJobFailurePayload.Reason.CONTEXT_MISMATCH);
		}

		HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noiseRegistry = worldgenRegistries.lookupOrThrow(Registries.NOISE);
		RandomState randomState = RandomState.create(settings.value(), noiseRegistry, job.worldSeed());
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
