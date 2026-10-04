package io.github.genichimaruo.worldgenassist.server;

import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.blending.Blender;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;

final class RemoteWorldgenEligibility {
	private RemoteWorldgenEligibility() {
	}

	static Optional<EligibleContext> evaluate(
		WorldGenContext context,
		ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk
	) {
		NoiseBasedChunkGenerator generator = noiseDelegate(context.generator());
		if (generator == null) {
			return Optional.empty();
		}
		if (!hasCompleteTerrainSource(generator)) return Optional.empty();
		ServerLevel level = context.level();
		if (chunk.isOldNoiseGeneration() || chunk.getBelowZeroRetrogen() != null) {
			return Optional.empty();
		}

		WorldGenRegion region = new WorldGenRegion(level, chunks, step, chunk);
		Blender blender = Blender.of(region);
		if (!blender.isEmpty()) {
			return Optional.empty();
		}
		StructureManager structureManager = level.structureManager().forWorldGenRegion(region);
		Beardifier beardifier = Beardifier.forStructuresInChunk(structureManager, chunk.getPos());
		io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData shaping;
		if (!io.github.genichimaruo.worldgenassist.common.CompleteTerrainMode.requested()) {
			if (beardifier != Beardifier.EMPTY) return Optional.empty();
			shaping = io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData.EMPTY;
		} else {
			try { shaping = io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData.capture(beardifier); }
			catch (IllegalArgumentException unsupported) { return Optional.empty(); }
		}

		Holder<NoiseGeneratorSettings> settings = generator.generatorSettings();
		if (settings.unwrapKey().isEmpty()) {
			return Optional.empty();
		}
		if (!shaping.empty() && io.github.genichimaruo.worldgenassist.common.SurfaceDensityData.selectedKind(
			settings.unwrapKey().orElseThrow().identifier(), settings.value()) != io.github.genichimaruo.worldgenassist.common.TerrainWorkKind.COMPLETE_TERRAIN) {
			return Optional.empty();
		}
		NoiseSettings noise = settings.value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
		if (!noise.equals(settings.value().noiseSettings()) || !hasProtocolGeometry(noise, level.getMinY(), level.getHeight())) {
			return Optional.empty();
		}
		return Optional.of(new EligibleContext(level, generator, settings, noise, structureManager, blender, shaping));
	}

	static Optional<SpeculativeContext> evaluatePrediction(ServerLevel level) {
		// A custom adapter may have its own demand rules. Wait for real chunk demand
		// rather than speculating before the wrapper enters its delegate path.
		if (level.getChunkSource().getGenerator().getClass() != NoiseBasedChunkGenerator.class) {
			return Optional.empty();
		}
		NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
		if (!hasCompleteTerrainSource(generator)) return Optional.empty();
		Holder<NoiseGeneratorSettings> settings = generator.generatorSettings();
		if (settings.unwrapKey().isEmpty()) {
			return Optional.empty();
		}
		NoiseSettings noise = settings.value().noiseSettings().clampToHeightAccessor(level);
		if (!noise.equals(settings.value().noiseSettings()) || !hasProtocolGeometry(noise, level.getMinY(), level.getHeight())) {
			return Optional.empty();
		}
		return Optional.of(new SpeculativeContext(level, generator, settings, noise));
	}

	static NoiseBasedChunkGenerator noiseDelegate(ChunkGenerator generator) {
		if (generator.getClass() == NoiseBasedChunkGenerator.class) {
			return (NoiseBasedChunkGenerator) generator;
		}
		if (generator instanceof RemoteDensityCompatibleGenerator compatible) {
			try {
				return compatible.worldgenAssist$noiseDelegate();
			} catch (RuntimeException exception) {
				return null;
			}
		}
		return null;
	}
	private static boolean hasCompleteTerrainSource(NoiseBasedChunkGenerator generator) {
		if (!io.github.genichimaruo.worldgenassist.common.CompleteTerrainMode.requested()) return true;
		return generator.getClass() == NoiseBasedChunkGenerator.class
			&& generator.getBiomeSource() instanceof net.minecraft.world.level.biome.MultiNoiseBiomeSource source
			&& source.stable(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD);
	}

	static boolean hasProtocolGeometry(NoiseSettings noise, int minY, int height) {
		return height > 0 && noise.minY() >= minY
			&& (long)noise.minY() + noise.height() <= (long)minY + height
			&& noise.height() <= TerrainDensityJob.MAX_HEIGHT
			&& noise.height() > 0
			&& noise.height() % 16 == 0
			&& Math.floorMod(noise.minY(), 16) == 0;
	}

	record EligibleContext(
		ServerLevel level,
		NoiseBasedChunkGenerator generator,
		Holder<NoiseGeneratorSettings> settings,
		NoiseSettings noise,
		StructureManager structureManager,
		Blender blender,
		io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData shaping
	) {
	}

	record SpeculativeContext(
		ServerLevel level,
		NoiseBasedChunkGenerator generator,
		Holder<NoiseGeneratorSettings> settings,
		NoiseSettings noise,
		io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData shaping
	) {
		SpeculativeContext(ServerLevel level, NoiseBasedChunkGenerator generator, Holder<NoiseGeneratorSettings> settings, NoiseSettings noise) {
			this(level, generator, settings, noise, io.github.genichimaruo.worldgenassist.common.TerrainBeardifierData.EMPTY);
		}
	}
}
