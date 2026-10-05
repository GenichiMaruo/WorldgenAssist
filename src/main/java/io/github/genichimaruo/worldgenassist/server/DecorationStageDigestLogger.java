package io.github.genichimaruo.worldgenassist.server;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.WorldGenContext;

public final class DecorationStageDigestLogger {
	public static final String PROPERTY = "worldgen_assist.decoration_digest";
	public static final String ENVIRONMENT = "WORLDGEN_ASSIST_DECORATION_DIGEST";
	private DecorationStageDigestLogger() {}
	public static boolean enabled() {
		return Boolean.getBoolean(PROPERTY) || "true".equalsIgnoreCase(System.getenv(ENVIRONMENT));
	}
	public static void log(WorldGenContext context, ChunkAccess chunk) {
		if (!enabled()) return;
		if (!(chunk instanceof ProtoChunk proto)) throw new IllegalStateException("decoration snapshot requires proto chunk");
		var result = DecorationStageDigest.compute(proto, context.level().registryAccess());
		WorldgenAssist.LOGGER.info(
			"[CAWG] stage.digest stage=decoration chunk={},{} format={} algorithm=SHA-256 digest={} terrain={} biomes={} heights={} block_entities={} entities={} ticks={} dimension={}",
			chunk.getPos().x(), chunk.getPos().z(), DecorationStageDigest.FORMAT, result.digest(), result.terrain(),
			result.biomes(), result.heights(), result.blockEntities(), result.entities(), result.ticks(), context.level().dimension().identifier());
	}
}
