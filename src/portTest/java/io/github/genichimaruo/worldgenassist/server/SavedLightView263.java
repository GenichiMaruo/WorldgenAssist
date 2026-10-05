package io.github.genichimaruo.worldgenassist.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;

/** Offline only. Read saved values with original missing-layer semantics, without repairing them. */
final class SavedLightView263 {
	private final SavedSky sky;
	private final SavedBlock block;
	SavedLightView263(LightChunkGetter source, ChunkPos pos, CompoundTag saved, java.util.List<Integer> allocatedSkySections) {
		SavedLightDigest.compute(saved); // Keep exact flag, array-length, section bounds and duplicate checks.
		if (saved.getInt("xPos").orElseThrow() != pos.x() || saved.getInt("zPos").orElseThrow() != pos.z())
			throw new IllegalArgumentException("saved light coordinate mismatch");
		sky = new SavedSky(source); block = new SavedBlock(source);
		// Original serializer omits allocated layers whose nibbles are all zero.
		// Restore only geometry/zero semantics, never any recomputed light value.
		for (int y : allocatedSkySections) {
			if (y < -5 || y > 20) throw new IllegalArgumentException("allocated sky section bounds");
			sky.load(SectionPos.asLong(pos.x(),y,pos.z()),new byte[2048]);
		}
		for (var entry : saved.getListOrEmpty("sections")) {
			var section = (CompoundTag)entry;
			long node = SectionPos.asLong(pos.x(), section.getByte("Y").orElseThrow(), pos.z());
			section.getByteArray("SkyLight").ifPresent(bytes -> sky.load(node, bytes));
			section.getByteArray("BlockLight").ifPresent(bytes -> block.load(node, bytes));
		}
		sky.publish(); block.publish();
	}
	int sky(BlockPos pos) { return sky.read(pos.asLong()); }
	int block(BlockPos pos) { return block.read(pos.asLong()); }
	private static final class SavedSky extends SkyLightSectionStorage {
		SavedSky(LightChunkGetter source) { super(source); }
		void load(long node, byte[] bytes) {
			updatingSectionData.setLayer(node, new DataLayer(bytes.clone()));
			onNodeAdded(node); changedSections.add(node);
		}
		void publish() { updatingSectionData.clearCache(); swapSectionMap(); }
		int read(long pos) { return getLightValue(pos); }
	}
	private static final class SavedBlock extends BlockLightSectionStorage {
		SavedBlock(LightChunkGetter source) { super(source); }
		void load(long node, byte[] bytes) {
			updatingSectionData.setLayer(node, new DataLayer(bytes.clone())); changedSections.add(node);
		}
		void publish() { updatingSectionData.clearCache(); swapSectionMap(); }
		int read(long pos) { return getLightValue(pos); }
	}
}
