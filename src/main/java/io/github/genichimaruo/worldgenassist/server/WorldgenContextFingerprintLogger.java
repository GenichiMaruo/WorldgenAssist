package io.github.genichimaruo.worldgenassist.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;

public final class WorldgenContextFingerprintLogger {
	public static final String SYSTEM_PROPERTY = "worldgen_assist.context_fingerprint";
	public static final String ENVIRONMENT_VARIABLE = "WORLDGEN_ASSIST_CONTEXT_FINGERPRINT";

	private WorldgenContextFingerprintLogger() {
	}

	public static boolean isEnabled() {
		return Boolean.getBoolean(SYSTEM_PROPERTY) || "true".equalsIgnoreCase(System.getenv(ENVIRONMENT_VARIABLE));
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				ChunkGenerator generator = level.getChunkSource().getGenerator();
				if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
					WorldgenAssist.LOGGER.warn(
						"[CAWG] context.fingerprint_unsupported dimension={} generator={}",
						level.dimension().identifier(),
						generator.getClass().getName()
					);
					continue;
				}

				long startedNanos = System.nanoTime();
				try {
					WorldgenContextFingerprint fingerprint = WorldgenContextFingerprintFactory.create(level, noiseGenerator);
					double elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000.0;
					WorldgenAssist.LOGGER.info(
						"[CAWG] context.fingerprint dimension={} format={} algorithm={} digest={} elapsed_ms={}",
						level.dimension().identifier(),
						WorldgenContextFingerprintFactory.FORMAT_VERSION,
						WorldgenContextFingerprint.ALGORITHM,
						fingerprint.toHex(),
						elapsedMillis
					);
				} catch (RuntimeException | Error error) {
					WorldgenAssist.LOGGER.warn(
						"[CAWG] context.fingerprint_failed dimension={}",
						level.dimension().identifier(),
						error
					);
				}
			}
		});
	}
}
