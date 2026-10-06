package io.github.genichimaruo.worldgenassist.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeSet;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Correctness fixture only. Independent name-based digest of ALL original
 * section voxels/palettes, without using the new transport encoding or approving data. */
public final class BiomeStageDigestLogger {
	private BiomeStageDigestLogger() { }
	public static boolean enabled() { return Boolean.getBoolean("worldgen_assist.biome.digest"); }
	public static void log(ChunkAccess chunk) {
		if (!enabled()) return;
		try {
			MessageDigest hash = MessageDigest.getInstance("SHA-256");
			hash.update("worldgen_assist:biome_stage_full_v1".getBytes(StandardCharsets.UTF_8));
			hash.update(ByteBuffer.allocate(16).putInt(chunk.getPos().x()).putInt(chunk.getPos().z())
				.putInt(chunk.getMinY()).putInt(chunk.getHeight()).array());
			for (var section : chunk.getSections()) {
				TreeSet<String> palette = new TreeSet<>();
				section.getBiomes().forEachInPalette(biome -> palette.add(biome.unwrapKey().orElseThrow().identifier().toString()));
				hash.update(ByteBuffer.allocate(4).putInt(palette.size()).array());
				for (String name : palette) name(hash,name);
				for (int y=0;y<4;y++) for (int z=0;z<4;z++) for (int x=0;x<4;x++) {
					name(hash,section.getNoiseBiome(x,y,z).unwrapKey().orElseThrow().identifier().toString());
				}
			}
			io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info("[CAWG] biome.digest chunk={},{} digest={}",
				chunk.getPos().x(),chunk.getPos().z(),java.util.HexFormat.of().formatHex(hash.digest()));
		} catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
	}
	private static void name(MessageDigest hash,String name) {
		byte[] value=name.getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(value.length).array());hash.update(value);
	}
}
