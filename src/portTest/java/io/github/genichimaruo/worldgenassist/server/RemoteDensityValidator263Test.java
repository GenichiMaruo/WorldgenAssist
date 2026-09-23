package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import java.util.UUID;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RemoteDensityValidator263Test {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@Timeout(45)
	void acceptsExactVolumeAndRejectsChangedSample() {
		HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
		NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
		var noise = settings.noiseSettings();
		RandomState state = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), 8675309L, settings);
		TerrainJobIdentity identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,
			UUID.randomUUID(), Identifier.parse("minecraft:overworld"), 7, -11,
			WorldgenContextFingerprint.fromBytes(new byte[32]));
		TerrainDensityJob job = new TerrainDensityJob(identity, 8675309L, true,
			NoiseGeneratorSettings.OVERWORLD.identifier(), noise.minY(), noise.height(), 1, 1);
		DensityVolume volume = new DensityVolume(16, noise.height(), 16, 7 * 16, noise.minY(), -11 * 16);
		double[] values = new double[volume.size()];
		try (ScopedDensityBuffer buffer = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED)
			.get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
			for (int index = 0; index < values.length; index++) { values[index] = buffer.get(index); }
		}
		TerrainDensityResult result = new TerrainDensityResult(identity, values, 1L);
		TerrainDensityResult received = TerrainDensityResultEnvelope.encode(result).decode();
		RemoteDensityField field = new RemoteDensityField(job, received);
		try (ScopedDensityBuffer copied = SamplerContext.EMPTY_UNCACHED.acquireBuffer(volume)) {
			field.copyVolume(volume, copied);
			for (int index = 0; index < values.length; index++) {
				assertEquals(Float.floatToRawIntBits((float) values[index]),
					Float.floatToRawIntBits(copied.get(index)), "transported index=" + index);
			}
		}
		assertEquals(128, RemoteDensityValidator.validate(job, received, 1, state, settings, noise,
			new Random(1)).sampledValues());

		int sampledGroup = RemoteDensityValidator.selectCells(values.length / 128, 1, new Random(1))[0];
		values[sampledGroup * 128] += 1.0;
		TerrainDensityResult altered = new TerrainDensityResult(identity, values, 1L);
		assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,
			() -> RemoteDensityValidator.validate(job, altered, 1, state, settings, noise, new Random(1)));
	}
}
