package io.github.genichimaruo.worldgenassist.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Exact already-generated 3x3 biome inputs; includes all vertical quart samples. */
public final class TerrainBiomeWindow {
	private TerrainBiomeWindow() { }
	public static byte[] digest(int centerX, int centerZ, int minY, int height,
		BiFunction<Integer, Integer, ChunkAccess> chunks) {
		if (height <= 0 || height > TerrainDensityJob.MAX_HEIGHT || height % 16 != 0 || minY % 16 != 0) {
			throw new IllegalArgumentException("Unsupported biome window height");
		}
		MessageDigest hash;
		try { hash = MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
		hash.update("worldgen_assist:terrain_biome_window_v2".getBytes(StandardCharsets.UTF_8));
		hash.update(ByteBuffer.allocate(16).putInt(centerX).putInt(centerZ).putInt(minY).putInt(height).array());
		Map<Holder<Biome>, Integer> codes = new IdentityHashMap<>();
		java.util.Set<Holder<Biome>> possibleBiomes = new java.util.HashSet<>();
		for (int z = centerZ - 1; z <= centerZ + 1; z++) for (int x = centerX - 1; x <= centerX + 1; x++) {
			Objects.requireNonNull(chunks.apply(x, z)).collectBiomesInPalette(possibleBiomes);
		}
		var ordered = possibleBiomes.stream().sorted(java.util.Comparator.comparing(biome ->
			biome.unwrapKey().orElseThrow().identifier().toString())).toList();
		if (ordered.isEmpty() || ordered.size() > 65535) throw new IllegalArgumentException("Unsupported biome palette size");
		hash.update(ByteBuffer.allocate(4).putInt(ordered.size()).array());
		for (int code = 0; code < ordered.size(); code++) {
			Holder<Biome> biome = ordered.get(code);
			codes.put(biome, code);
			String name = biome.unwrapKey().orElseThrow().identifier().toString();
			byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
			if (bytes.length > 256) throw new IllegalArgumentException("Biome ID too large");
			hash.update((byte)(bytes.length >>> 8)); hash.update((byte)bytes.length); hash.update(bytes);
		}
		// The sorted names bind these private IDs; they are never registry numeric IDs.
		// Every quart sample is retained, but hash one bounded buffer instead of
		// re-hashing biome-name strings with three update calls for every voxel.
		byte[] samples = new byte[9 * 16 * (height / 4) * 2];
		int cursor = 0, maxY = Math.addExact(minY, height);
		for (int z = centerZ - 1; z <= centerZ + 1; z++) for (int x = centerX - 1; x <= centerX + 1; x++) {
			if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
			ChunkAccess chunk = Objects.requireNonNull(chunks.apply(x, z));
			if (chunk.getPos().x() != x || chunk.getPos().z() != z || chunk.getMinY() != minY || chunk.getHeight() != height) {
				throw new IllegalArgumentException("Wrong biome window chunk");
			}
			int quartX = Math.multiplyExact(x, 4), quartZ = Math.multiplyExact(z, 4);
			for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) {
				for (int y = minY / 4; y < maxY / 4; y++) {
					Holder<Biome> biome = chunk.getNoiseBiome(quartX + qx, y, quartZ + qz);
					Integer code = codes.get(biome);
					if (code == null) throw new IllegalArgumentException("Biome voxel missing from window palette");
					samples[cursor++] = (byte)(code >>> 8); samples[cursor++] = (byte)(int)code;
				}
			}
		}
		hash.update(samples);
		return hash.digest();
	}
}
