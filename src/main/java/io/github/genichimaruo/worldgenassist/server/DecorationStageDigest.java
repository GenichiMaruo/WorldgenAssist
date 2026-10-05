package io.github.genichimaruo.worldgenassist.server;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.ticks.SavedTick;

/** Final decoration input to SPAWN: all neighboring FEATURES writes are dependencies of LIGHT. */
public final class DecorationStageDigest {
	public static final int FORMAT = 1;
	private DecorationStageDigest() {}

	public static Result compute(ProtoChunk chunk, HolderLookup.Provider registries) {
		String terrain = NoiseStageDigest.compute(chunk).digest(); // All blocks, WG heights and ordered postprocessing.
		String biomes = hash(out -> {
			out.writeInt(chunk.getSections().length);
			for (var section : chunk.getSections()) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
				out.writeUTF(section.getBiomes().get(x, y, z).unwrapKey().orElseThrow().identifier().toString());
		});
		String heights = hash(out -> {
			var entries = chunk.getHeightmaps().stream().sorted(Comparator.comparing(entry -> entry.getKey().getSerializationKey())).toList();
			out.writeInt(entries.size());
			for (var entry : entries) {
				out.writeUTF(entry.getKey().getSerializationKey());
				long[] values = entry.getValue().getRawData(); out.writeInt(values.length);
				for (long value : values) out.writeLong(value);
			}
		});
		String blockEntities = hash(out -> {
			var positions = chunk.getBlockEntitiesPos().stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
			out.writeInt(positions.size());
			for (var position : positions) {
				out.writeLong(position.asLong());
				writeTag(out, chunk.getBlockEntityNbtForSaving(position, registries), false);
			}
		});
		String entities = hash(out -> {
			out.writeInt(chunk.getEntities().size());
			// Exclude only random entity UUID allocation; preserve all other fields, list order and duplicates.
			for (var entity : chunk.getEntities()) writeTag(out, entity, true);
		});
		String ticks = hash(out -> {
			var packed = chunk.getTicksForSerialization(0L);
			writeTicks(out, packed.blocks(), type -> BuiltInRegistries.BLOCK.getKey(type).toString());
			writeTicks(out, packed.fluids(), type -> BuiltInRegistries.FLUID.getKey(type).toString());
		});
		String combined = hash(out -> {
			out.writeUTF("worldgen_assist:decoration_final"); out.writeInt(FORMAT);
			out.writeInt(chunk.getPos().x()); out.writeInt(chunk.getPos().z());
			out.writeInt(chunk.getMinY()); out.writeInt(chunk.getHeight());
			for (String part : List.of(terrain, biomes, heights, blockEntities, entities, ticks)) out.writeUTF(part);
		});
		return new Result(combined, terrain, biomes, heights, blockEntities, entities, ticks);
	}

	private static <T> void writeTicks(DataOutputStream out, List<SavedTick<T>> ticks, java.util.function.Function<T,String> name) throws IOException {
		out.writeInt(ticks.size());
		for (var tick : ticks) {
			out.writeUTF(name.apply(tick.type())); out.writeLong(tick.pos().asLong());
			out.writeInt(tick.delay()); out.writeUTF(tick.priority().name());
		}
	}
	static void writeTag(DataOutputStream out, Tag tag, boolean excludeEntityUuid) throws IOException {
		if (tag == null) { out.writeByte(-1); return; }
		out.writeByte(tag.getId());
		if (tag instanceof CompoundTag compound) {
			var keys = compound.keySet().stream().filter(key -> !excludeEntityUuid || !key.equals("UUID")).sorted().toList();
			out.writeInt(keys.size());
			for (String key : keys) { out.writeUTF(key); writeTag(out, compound.get(key), excludeEntityUuid); }
		} else if (tag instanceof ListTag list) {
			out.writeInt(list.size()); for (Tag child : list) writeTag(out, child, excludeEntityUuid);
		} else tag.write(out);
	}
	static String hash(Writer writer) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) { writer.write(out); }
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException error) { throw new IllegalStateException("decoration digest", error); }
	}
	@FunctionalInterface interface Writer { void write(DataOutputStream output) throws IOException; }
	public record Result(String digest, String terrain, String biomes, String heights, String blockEntities, String entities, String ticks) {}
}
