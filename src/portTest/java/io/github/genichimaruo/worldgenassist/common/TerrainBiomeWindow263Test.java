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
	}
}
