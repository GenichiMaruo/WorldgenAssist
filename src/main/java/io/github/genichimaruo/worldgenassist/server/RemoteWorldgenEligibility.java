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
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.blending.Blender;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.SupportedDimensions;

final class RemoteWorldgenEligibility {
	private RemoteWorldgenEligibility() {
	}

	static Optional<EligibleContext> evaluate(
		WorldGenContext context,
		ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk
	) {
		if (!(context.generator() instanceof NoiseBasedChunkGenerator generator)) {
			return Optional.empty();
		}
		ServerLevel level = context.level();
		if (!SupportedDimensions.contains(level.dimension().identifier()) || chunk.isOldNoiseGeneration() || chunk.getBelowZeroRetrogen() != null) {
			return Optional.empty();
		}

		WorldGenRegion region = new WorldGenRegion(level, chunks, step, chunk);
		Blender blender = Blender.of(region);
		if (!blender.isEmpty()) {
			return Optional.empty();
		}
		StructureManager structureManager = level.structureManager().forWorldGenRegion(region);
		if (Beardifier.forStructuresInChunk(structureManager, chunk.getPos()) != Beardifier.EMPTY) {
			return Optional.empty();
		}

		Holder<NoiseGeneratorSettings> settings = generator.generatorSettings();
		if (settings.unwrapKey().isEmpty()) {
			return Optional.empty();
		}
		NoiseSettings noise = settings.value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
		if (!noise.equals(settings.value().noiseSettings()) || !hasProtocolGeometry(noise, level.getMinY(), level.getHeight())) {
			return Optional.empty();
		}
		return Optional.of(new EligibleContext(level, generator, settings, noise, structureManager, blender));
	}

	static Optional<SpeculativeContext> evaluatePrediction(ServerLevel level) {
		if (!SupportedDimensions.contains(level.dimension().identifier())
			|| !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
			return Optional.empty();
		}
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

	static boolean hasProtocolGeometry(NoiseSettings noise, int minY, int height) {
		return height > 0 && noise.minY() >= minY
			&& (long)noise.minY() + noise.height() <= (long)minY + height
			&& noise.height() <= TerrainDensityJob.MAX_HEIGHT
			&& noise.height() > 0
			&& noise.getCellWidth() > 0
			&& TerrainDensityJob.CHUNK_SIDE % noise.getCellWidth() == 0
			&& noise.getCellHeight() > 0
			&& noise.height() % noise.getCellHeight() == 0
			&& Math.floorMod(noise.minY(), noise.getCellHeight()) == 0;
	}

	record EligibleContext(
		ServerLevel level,
		NoiseBasedChunkGenerator generator,
		Holder<NoiseGeneratorSettings> settings,
		NoiseSettings noise,
		StructureManager structureManager,
		Blender blender
	) {
	}

	record SpeculativeContext(
		ServerLevel level,
		NoiseBasedChunkGenerator generator,
		Holder<NoiseGeneratorSettings> settings,
		NoiseSettings noise
	) {
	}
}
