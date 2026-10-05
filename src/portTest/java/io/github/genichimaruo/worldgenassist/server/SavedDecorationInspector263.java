package io.github.genichimaruo.worldgenassist.server;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/** Offline evidence only: uses Minecraft's original region/NBT/palette codecs, no world generation. */
public final class SavedDecorationInspector263 {
	private static final int MIN_SECTION = -4, SECTION_COUNT = 24, BLOCKS_PER_SECTION = 4096;
	private static final int VOXELS = SECTION_COUNT * BLOCKS_PER_SECTION;
	private SavedDecorationInspector263() {}
	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("one inspection descriptor required");
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		var descriptor = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
		Path output = Path.of(descriptor.get("output").getAsString());
		if (Files.exists(output)) throw new IllegalArgumentException("inspection output must be new");
		List<String> coordinates = new ArrayList<>();
		for (var entry : descriptor.getAsJsonArray("coordinates")) coordinates.add(entry.getAsString());
		if (coordinates.size() != 882 || coordinates.stream().distinct().count() != 882)
			throw new IllegalArgumentException("entire two-owner fixture inventory required");
		List<Map<String,Object>> comparisons = new ArrayList<>();
		var cases = descriptor.getAsJsonObject("cases");
		try (var baseline = new Reader(Path.of(cases.get("original").getAsString()))) {
			for (var entry : cases.entrySet()) {
				if (entry.getKey().equals("original")) continue;
				long changedBlocks = 0; int changedChunks = 0, changedLightChunks = 0, changedHeightmapChunks = 0, changedBlockEntityChunks = 0;
				Map<String,Long> transitions = new TreeMap<>(); Map<Integer,Long> heights = new TreeMap<>();
				List<Map<String,Object>> examples = new ArrayList<>();
				List<Map<String,Object>> lightExamples = new ArrayList<>();
				List<Map<String,Object>> blockEntityExamples = new ArrayList<>();
				try (var candidate = new Reader(Path.of(entry.getValue().getAsString()))) {
					for (String coordinate : coordinates) {
						String[] parts = coordinate.split(",", -1);
						if (parts.length != 2) throw new IllegalArgumentException("chunk coordinate");
						var pos = new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
						var leftTag = baseline.tag(pos); var rightTag = candidate.tag(pos);
						int[] left = baseline.blocks(leftTag), right = candidate.blocks(rightTag);
						if (!hash(leftTag.get("Heightmaps")).equals(hash(rightTag.get("Heightmaps")))) changedHeightmapChunks++;
						if (!hash(leftTag.get("block_entities")).equals(hash(rightTag.get("block_entities")))) {
							changedBlockEntityChunks++;
							if (blockEntityExamples.size() < 20) blockEntityExamples.add(Map.of("chunk", coordinate,
								"baseline", String.valueOf(leftTag.get("block_entities")), "candidate", String.valueOf(rightTag.get("block_entities"))));
						}
						if (!SavedLightDigest.compute(leftTag).equals(SavedLightDigest.compute(rightTag))) {
							changedLightChunks++;
							if (lightExamples.size() < 30) lightExamples.add(Map.of("chunk", coordinate, "sections", lightChanges(leftTag, rightTag)));
						}
						long count = 0;
						for (int index = 0; index < VOXELS; index++) if (left[index] != right[index]) {
							count++;
							String transition = NoiseStageDigest.canonicalBlockState(Block.stateById(left[index])) + " -> "
								+ NoiseStageDigest.canonicalBlockState(Block.stateById(right[index]));
							transitions.merge(transition, 1L, Long::sum);
							int y = MIN_SECTION * 16 + index / 256; heights.merge(y, 1L, Long::sum);
						}
						if (count > 0) { changedChunks++; changedBlocks += count; if (examples.size() < 20) examples.add(Map.of("chunk", coordinate, "changed_blocks", count)); }
					}
				}
				var comparison = new TreeMap<String,Object>();
				comparison.putAll(Map.of("candidate", entry.getKey(), "required_chunks", coordinates.size(), "changed_chunks", changedChunks,
					"changed_blocks", changedBlocks, "block_transitions", transitions, "changed_y", heights, "examples", examples));
				comparison.put("changed_saved_heightmap_chunks", changedHeightmapChunks);
				comparison.put("changed_light_chunks", changedLightChunks); comparison.put("light_examples", lightExamples);
				comparison.put("changed_saved_block_entity_chunks", changedBlockEntityChunks); comparison.put("block_entity_examples", blockEntityExamples);
				comparisons.add(comparison);
			}
		}
		var report = Map.of("schema", "worldgen-assist.saved-block-inspection.v1", "comparisons", comparisons,
			"scope", "Every saved block voxel, final heightmap tag and exact light bytes/presence in 882 stock Overworld FULL chunks; excludes mobs, ticking history and intermediate postprocessing order. Original game codecs; no new generation.");
		try (BufferedWriter writer = Files.newBufferedWriter(output)) { new GsonBuilder().setPrettyPrinting().create().toJson(report, writer); }
		System.out.println("SAVED_BLOCK_INSPECTION report=" + output);
	}
	private static String hash(net.minecraft.nbt.Tag tag) { return DecorationStageDigest.hash(out -> DecorationStageDigest.writeTag(out, tag, false)); }
	private static Map<Integer,net.minecraft.nbt.CompoundTag> lightSections(net.minecraft.nbt.CompoundTag chunk) {
		var sections = new TreeMap<Integer,net.minecraft.nbt.CompoundTag>();
		for (var entry : chunk.getListOrEmpty("sections")) {
			var section = (net.minecraft.nbt.CompoundTag)entry; sections.put((int)section.getByte("Y").orElseThrow(),section);
		}
		return sections;
	}
	private static List<Map<String,Object>> lightChanges(net.minecraft.nbt.CompoundTag left, net.minecraft.nbt.CompoundTag right) {
		var a = lightSections(left); var b = lightSections(right); var changes = new ArrayList<Map<String,Object>>();
		for (int y = -5; y <= 20; y++) for (String layer : List.of("SkyLight", "BlockLight")) {
			byte[] l = a.containsKey(y) ? a.get(y).getByteArray(layer).orElse(null) : null;
			byte[] r = b.containsKey(y) ? b.get(y).getByteArray(layer).orElse(null) : null;
			if (Arrays.equals(l,r)) continue;
			int different = 0, low = 0, high = 0;
			if (l != null && r != null) for (int index = 0; index < 2048; index++) {
				if (l[index] != r[index]) different++;
				if ((l[index]&15) != (r[index]&15)) low++;
				if ((l[index]&240) != (r[index]&240)) high++;
			}
			changes.add(Map.of("y", y, "layer", layer, "baseline_present", l != null, "candidate_present", r != null,
				"changed_bytes", different, "changed_nibbles", low+high));
		}
		return changes;
	}
	private static final class Reader implements AutoCloseable {
		private final Path directory;
		private final Map<ChunkPos, RegionFile> regions = new HashMap<>();
		private final com.mojang.serialization.Codec<PalettedContainer<BlockState>> codec = PalettedContainer.codecRW(
			BlockState.CODEC, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
		Reader(Path directory) { this.directory = directory; }
		net.minecraft.nbt.CompoundTag tag(ChunkPos pos) throws Exception {
			var regionPos = new ChunkPos(pos.getRegionX(), pos.getRegionZ());
			RegionFile region = regions.get(regionPos);
			if (region == null) {
				Path path = directory.resolve("r." + regionPos.x() + "." + regionPos.z() + ".mca");
				if (!Files.isRegularFile(path)) throw new IllegalArgumentException("missing retained region " + path);
				region = new RegionFile(new RegionStorageInfo("worldgen_assist_retained_fixture", Level.OVERWORLD, "chunk"), path, directory, false);
				regions.put(regionPos, region);
			}
			try (var input = region.getChunkDataInputStream(pos)) {
				if (input == null) throw new IllegalArgumentException("missing saved chunk " + pos);
				var tag = NbtIo.read(input, NbtAccounter.create(16L * 1024 * 1024));
				if (!tag.getString("Status").orElseThrow().equals("minecraft:full") || tag.getInt("xPos").orElseThrow() != pos.x()
					|| tag.getInt("zPos").orElseThrow() != pos.z()) throw new IllegalArgumentException("saved FULL identity mismatch");
				return tag;
			}
		}
		int[] blocks(net.minecraft.nbt.CompoundTag tag) {
				int[] blocks = new int[VOXELS]; Arrays.fill(blocks, Block.getId(Blocks.AIR.defaultBlockState())); boolean[] seen = new boolean[SECTION_COUNT];
				for (var sectionTag : tag.getListOrEmpty("sections")) {
					if (!(sectionTag instanceof net.minecraft.nbt.CompoundTag section)) throw new IllegalArgumentException("section tag");
					int sectionY = section.getByte("Y").orElseThrow(); int sectionIndex = sectionY - MIN_SECTION;
					if (sectionIndex < 0 || sectionIndex >= SECTION_COUNT) continue; // Original extra light sections contain no world voxels.
					if (seen[sectionIndex]) throw new IllegalArgumentException("duplicate saved section"); seen[sectionIndex] = true;
					var data = section.getCompound("block_states");
					if (data.isEmpty()) continue; // Original serializer uses AIR for missing block storage.
					var states = codec.parse(NbtOps.INSTANCE, data.get()).getOrThrow();
					for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
						blocks[sectionIndex * BLOCKS_PER_SECTION + y * 256 + z * 16 + x] = Block.getId(states.get(x,y,z));
				}
				return blocks;
		}
		@Override public void close() throws Exception { for (var region : regions.values()) region.close(); }
	}
}
