package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;

class TerrainBiomeWindow263Test {
	private static Map<ChunkPos, ProtoChunk> window() {
		var result = new HashMap<ChunkPos, ProtoChunk>();
		for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
			var pos = new ChunkPos(x, z); result.put(pos, PrivateBiomeCache263Test.chunk(pos));
		}
		return result;
	}
	private static PalettedContainer<Holder<Biome>> palette(ProtoChunk chunk) {
		return (PalettedContainer<Holder<Biome>>)chunk.getSection(0).getBiomes();
	}
	private static byte[] digest(Map<ChunkPos, ProtoChunk> chunks) {
		return TerrainBiomeWindow.digest(0, 0, 0, 16, (x, z) -> chunks.get(new ChunkPos(x, z)));
	}
	@Test void namesBindCodesIndependentlyOfPaletteInsertionAndUnusedEntriesStillMatter() {
		var a = window(); var b = window(); byte[] initial = digest(a);
		var pa = palette(a.get(new ChunkPos(0, 0))); var pb = palette(b.get(new ChunkPos(0, 0)));
		pa.getAndSetUnchecked(0, 0, 0, PrivateBiomeCache263Test.DESERT);
		pa.getAndSetUnchecked(0, 0, 0, PrivateBiomeCache263Test.FOREST);
		pa.getAndSetUnchecked(0, 0, 0, PrivateBiomeCache263Test.PLAINS);
		pb.getAndSetUnchecked(0, 0, 0, PrivateBiomeCache263Test.FOREST);
		pb.getAndSetUnchecked(0, 0, 0, PrivateBiomeCache263Test.DESERT);
		pb.getAndSetUnchecked(0, 0, 0, PrivateBiomeCache263Test.PLAINS);
		assertArrayEquals(digest(a), digest(b));
		assertFalse(Arrays.equals(initial, digest(a))); // Unused entries affect possibleBiomes.
		var reads = new java.util.concurrent.atomic.AtomicInteger();
		var inspected = TerrainBiomeWindow.inspect(0, 0, 0, 16, (x, z) -> { reads.incrementAndGet(); return a.get(new ChunkPos(x, z)); });
		assertEquals(9, reads.get());
		assertEquals(java.util.Set.of(PrivateBiomeCache263Test.PLAINS, PrivateBiomeCache263Test.DESERT, PrivateBiomeCache263Test.FOREST), inspected.possibleBiomes());
		assertThrows(UnsupportedOperationException.class, inspected.possibleBiomes()::clear);
		byte[] hash = inspected.digest(); hash[0] ^= 1;
		assertArrayEquals(digest(a), inspected.digest());
	}
	@Test void everyCornerVoxelAndGeometryAreBoundEvenWithUnchangedPossibleBiomeSet() {
		var chunks = window(); var corner = chunks.get(new ChunkPos(-1, -1)); var p = palette(corner);
		p.getAndSetUnchecked(3, 3, 3, PrivateBiomeCache263Test.DESERT);
		p.getAndSetUnchecked(3, 3, 3, PrivateBiomeCache263Test.PLAINS);
		byte[] baseline = digest(chunks);
		p.getAndSetUnchecked(3, 3, 3, PrivateBiomeCache263Test.DESERT);
		assertFalse(Arrays.equals(baseline, digest(chunks)));
		assertThrows(IllegalArgumentException.class, () -> TerrainBiomeWindow.digest(0, 0, 0, 15,
			(x, z) -> chunks.get(new ChunkPos(x, z))));
		assertThrows(IllegalArgumentException.class, () -> TerrainBiomeWindow.digest(0, 0, 16, 16,
			(x, z) -> chunks.get(new ChunkPos(x, z))));
		assertThrows(IllegalArgumentException.class, () -> TerrainBiomeWindow.digest(0, 0, 0, 16,
			(x, z) -> chunks.get(new ChunkPos(0, 0))));
		// Full stock geometry, negative coordinates/Y, distinct quart corners and section boundaries.
		var large = new HashMap<ChunkPos, ProtoChunk>();
		for (int z = -5; z <= -3; z++) for (int x = -8; x <= -6; x++) {
			var pos = new ChunkPos(x, z);
			var chunk = new ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
				net.minecraft.world.level.LevelHeightAccessor.create(-64, 384), PrivateBiomeCache263Test.CONTAINERS, null);
			chunk.fillBiomesFromNoise((qx, qy, qz) -> switch (Math.floorMod(qx + 3 * qy + 7 * qz, 3)) {
				case 0 -> PrivateBiomeCache263Test.PLAINS; case 1 -> PrivateBiomeCache263Test.DESERT; default -> PrivateBiomeCache263Test.FOREST;
			});
			chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
			large.put(pos, chunk);
		}
		assertArrayEquals(originalDigest(-7, -4, -64, 384, large),
			TerrainBiomeWindow.digest(-7, -4, -64, 384, (x, z) -> large.get(new ChunkPos(x, z))));
		var ungenerated = large.get(new ChunkPos(-8, -5));
		ungenerated.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY);
		assertThrows(IllegalStateException.class, () -> TerrainBiomeWindow.digest(-7, -4, -64, 384,
			(x, z) -> large.get(new ChunkPos(x, z))));
		ungenerated.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
		var pos = new ChunkPos(-7, -4);
		var overridden = new ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
			net.minecraft.world.level.LevelHeightAccessor.create(-64, 384), PrivateBiomeCache263Test.CONTAINERS, null) {
			@Override public Holder<Biome> getNoiseBiome(int x, int y, int z) { return PrivateBiomeCache263Test.DESERT; }
		};
		overridden.fillBiomesFromNoise((x, y, z) -> PrivateBiomeCache263Test.DESERT);
		overridden.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
		large.put(pos, overridden);
		// Getter intentionally differs from actual section voxels, with DESERT retained unused.
		for (var section : overridden.getSections()) {
			var values = (PalettedContainer<Holder<Biome>>)section.getBiomes();
			for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++)
				values.getAndSetUnchecked(x, y, z, PrivateBiomeCache263Test.PLAINS);
		}
		assertArrayEquals(originalDigest(-7, -4, -64, 384, large),
			TerrainBiomeWindow.digest(-7, -4, -64, 384, (x, z) -> large.get(new ChunkPos(x, z))));
	}
	/** Independent protocol framing using the original game getter for every quart voxel. */
	private static byte[] originalDigest(int cx, int cz, int minY, int height, Map<ChunkPos, ProtoChunk> chunks) {
		try {
			var bytes = new java.io.ByteArrayOutputStream(); var out = new java.io.DataOutputStream(bytes);
			out.write("worldgen_assist:terrain_biome_window_v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.writeInt(cx); out.writeInt(cz); out.writeInt(minY); out.writeInt(height);
			var possible = new java.util.HashSet<Holder<Biome>>(); chunks.values().forEach(c -> c.collectBiomesInPalette(possible));
			var names = possible.stream().map(b -> b.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
			out.writeInt(names.size());
			for (String name : names) { var encoded = name.getBytes(java.nio.charset.StandardCharsets.UTF_8); out.writeShort(encoded.length); out.write(encoded); }
			for (int z = cz - 1; z <= cz + 1; z++) for (int x = cx - 1; x <= cx + 1; x++) {
				var chunk = chunks.get(new ChunkPos(x, z));
				for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) for (int y = minY / 4; y < (minY + height) / 4; y++)
					out.writeShort(names.indexOf(chunk.getNoiseBiome(x * 4 + qx, y, z * 4 + qz).unwrapKey().orElseThrow().identifier().toString()));
			}
			return java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
		} catch (java.io.IOException | java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
	}
}
