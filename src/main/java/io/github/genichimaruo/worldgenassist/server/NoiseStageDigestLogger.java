package io.github.genichimaruo.worldgenassist.server;

import net.minecraft.world.level.chunk.ChunkAccess;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;

public final class NoiseStageDigestLogger {
	public static final String SYSTEM_PROPERTY = "worldgen_assist.noise_digest";
	public static final String ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_NOISE_DIGEST";

	private NoiseStageDigestLogger() {
	}

	public static boolean isEnabled() {
		return Boolean.getBoolean(SYSTEM_PROPERTY) || "true".equalsIgnoreCase(System.getenv(ENVIRONMENT_VARIABLE));
	}

	public static void computeAndLog(ChunkAccess chunk) {
		long startedNanos = System.nanoTime();
		try {
			NoiseStageDigest.Result result = NoiseStageDigest.compute(chunk);
			double elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000.0;
			WorldgenAssist.LOGGER.info(
				"[CAWG] stage.digest stage=noise chunk={},{} format={} algorithm={} digest={} digest_ms={} blocks={} heightmap_longs={} post_processing={}",
				chunk.getPos().x(),
				chunk.getPos().z(),
				result.formatVersion(),
				result.algorithm(),
				result.digest(),
				elapsedMillis,
				result.blockCount(),
				result.heightmapLongCount(),
				result.postProcessingCount()
			);
		} catch (RuntimeException | Error error) {
			WorldgenAssist.LOGGER.warn(
				"[CAWG] stage.digest_failed stage=noise chunk={},{}",
				chunk.getPos().x(),
				chunk.getPos().z(),
				error
			);
		}
	}
}
