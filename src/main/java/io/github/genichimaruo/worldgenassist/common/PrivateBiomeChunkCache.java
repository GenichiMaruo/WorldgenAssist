package io.github.genichimaruo.worldgenassist.common;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Thread-confined private BIOMES only. Cached chunks must never enter terrain computation. */
final class PrivateBiomeChunkCache {
	private final int capacity;
	private final LinkedHashMap<ChunkPos, ProtoChunk> chunks = new LinkedHashMap<>(64, .75f, true);
	private long hits, misses;
	PrivateBiomeChunkCache(int capacity) {
		if (capacity < 1 || capacity > 512) throw new IllegalArgumentException("Invalid private biome capacity");
		this.capacity = capacity;
	}
	void clear() { chunks.clear(); hits = 0; misses = 0; }
	long hits() { return hits; }
	long misses() { return misses; }
	int size() { return chunks.size(); }
	ProtoChunk canonical(ChunkPos pos, Supplier<ProtoChunk> create) {
		ProtoChunk cached = chunks.get(pos);
		if (cached != null) { hits++; return cached; }
		ProtoChunk chunk = Objects.requireNonNull(create.get());
		if (!pos.equals(chunk.getPos()) || chunk.getPersistedStatus() != ChunkStatus.BIOMES) {
			throw new IllegalArgumentException("Invalid private biome cache source");
		}
		for (var section : chunk.getSections()) if (!section.hasOnlyAir()) {
			throw new IllegalArgumentException("Private biome cache source contains terrain");
		}
		chunks.put(pos, chunk);
		misses++;
		if (chunks.size() > capacity) chunks.remove(chunks.keySet().iterator().next());
		return chunk;
	}
	static ProtoChunk copyCenter(ProtoChunk source, PalettedContainerFactory containers) {
		ProtoChunk copy = new ProtoChunk(source.getPos(), UpgradeData.EMPTY, source, containers, null);
		for (int i = 0; i < source.getSections().length; i++) copy.getSections()[i] = source.getSection(i).copy();
		copy.setPersistedStatus(ChunkStatus.BIOMES);
		return copy;
	}
}
