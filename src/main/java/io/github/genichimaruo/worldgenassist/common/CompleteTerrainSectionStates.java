package io.github.genichimaruo.worldgenassist.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/** Immutable process-local representation, built only from the fixed terrain domain.
 * Contains no live chunk, biome, section, authority or retained Netty buffer.
 */
public final class CompleteTerrainSectionStates {
	private final byte[][] sections;
	private CompleteTerrainSectionStates(byte[][] sections) { this.sections = sections; }

	static CompleteTerrainSectionStates prepare(CompleteTerrainData data) {
		var palette = new CompleteTerrainPalette();
		BlockState[] states = new BlockState[palette.size()];
		for (int i = 0; i < states.length; i++) states[i] = palette.state(i);
		Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
		byte[][] packed = new byte[data.height() / 16][];
		// Original SimpleBitStorage copies the indices before the next section.
		int[] ids = new int[4096], codes = new int[states.length];
		List<BlockState> entries = new ArrayList<>(states.length);
		for (int section = 0; section < packed.length; section++) {
			Arrays.fill(codes, -1); entries.clear();
			for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
				int code = data.choice((z * 16 + x) * data.height() + section * 16 + y);
				if (codes[code] < 0) { codes[code] = entries.size(); entries.add(states[code]); }
				ids[strategy.getIndex(x, y, z)] = codes[code];
			}
			int bits = entries.size() == 1 ? 0 : Math.max(4, Mth.ceillog2(entries.size()));
			var storage = bits == 0 ? Optional.<java.util.stream.LongStream>empty()
				: Optional.of(Arrays.stream(new SimpleBitStorage(bits, 4096, ids).getRaw()));
			var container = PalettedContainer.unpack(strategy,
				new PalettedContainerRO.PackedData<>(List.copyOf(entries), storage, bits)).getOrThrow();
			var buffer = new FriendlyByteBuf(Unpooled.buffer(container.getSerializedSize()));
			try { container.write(buffer); packed[section] = new byte[buffer.readableBytes()]; buffer.readBytes(packed[section]); }
			finally { buffer.release(); }
		}
		return new CompleteTerrainSectionStates(packed);
	}

	/** Checks every target strategy before any world write. Does not own a target. */
	public void preflight(LevelChunkSection[] target) {
		requireGeometry(target);
		for (int i = 0; i < sections.length; i++) {
			var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(sections[i]));
			try { target[i].getStates().recreate().read(buffer);
				if (buffer.isReadable()) throw new IllegalArgumentException("Packed terrain has trailing state data");
			} finally { buffer.release(); }
		}
	}
	/** Generation thread, after preflight/current authority. Original locks and counters. */
	public void apply(LevelChunkSection[] target) {
		requireGeometry(target);
		for (int i = 0; i < sections.length; i++) {
			var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(sections[i]));
			try { target[i].getStates().read(buffer); }
			finally { buffer.release(); }
			target[i].recalcBlockCounts();
		}
	}
	private void requireGeometry(LevelChunkSection[] target) {
		if (sections.length != target.length) throw new IllegalArgumentException("Packed terrain section geometry differs");
	}
}
