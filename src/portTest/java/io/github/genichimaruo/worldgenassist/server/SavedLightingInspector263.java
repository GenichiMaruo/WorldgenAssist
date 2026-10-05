package io.github.genichimaruo.worldgenassist.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Offline original light propagation on each world's own saved blocks, never another world generation. */
public final class SavedLightingInspector263 {
	private static final int MIN_Y = -64, HEIGHT = 384, TARGET_RADIUS = 2, HALO_RADIUS = 4;
	private SavedLightingInspector263() {}
	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("one saved lighting descriptor required");
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		Path evidence = Path.of("test-artifacts").toRealPath();
		Path descriptorPath = Path.of(args[0]).toRealPath();
		if (!descriptorPath.startsWith(evidence)) throw new IllegalArgumentException("owned descriptor required");
		var descriptor = JsonParser.parseString(Files.readString(descriptorPath)).getAsJsonObject();
		Path output = Path.of(descriptor.get("output").getAsString()).toAbsolutePath().normalize();
		if (!output.startsWith(evidence) || output.equals(evidence) || Files.exists(output)) throw new IllegalArgumentException("new owned output required");
		var centers = new ArrayList<ChunkPos>();
		for (var value : descriptor.getAsJsonArray("centers")) {
			var center = value.getAsJsonObject(); int x = center.get("x").getAsInt(), z = center.get("z").getAsInt();
			if (Math.abs((long)x) > 1_000_000 || Math.abs((long)z) > 1_000_000) throw new IllegalArgumentException("center bounds");
			centers.add(new ChunkPos(x,z));
		}
		if (centers.size() != 2 || centers.get(0).getChessboardDistance(centers.get(1)) <= HALO_RADIUS * 2)
			throw new IllegalArgumentException("two disjoint measured owner centers required");
		var cases = descriptor.getAsJsonObject("cases");
		if (cases.size() < 1 || cases.size() > 6) throw new IllegalArgumentException("case bounds");
		var reports = new ArrayList<Map<String,Object>>(); boolean success = true;
		for (var entry : cases.entrySet()) {
			Path directory = Path.of(entry.getValue().getAsString()).toRealPath();
			if (!directory.startsWith(evidence)) throw new IllegalArgumentException("owned retained region required");
			var chunks = new HashMap<ChunkPos,ProtoChunk>(); var saved = new HashMap<ChunkPos,CompoundTag>();
			var containers = containers();
			var codec = PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
			try (var reader = new Reader(directory)) {
				for (var center : centers) for (int z = -HALO_RADIUS; z <= HALO_RADIUS; z++) for (int x = -HALO_RADIUS; x <= HALO_RADIUS; x++) {
					var pos = new ChunkPos(center.x()+x,center.z()+z); var tag = reader.tag(pos);
					SavedLightDigest.compute(tag);
					var chunk = new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(MIN_Y,HEIGHT),containers,null);
					for (var sectionEntry : tag.getListOrEmpty("sections")) {
						var section = (CompoundTag)sectionEntry; int index = section.getByte("Y").orElseThrow() + 4;
						if (index < 0 || index >= HEIGHT/16) continue; // Exterior light sections have no world blocks.
						var data = section.getCompound("block_states");
						if (data.isPresent()) chunk.getSections()[index] = new LevelChunkSection(codec.parse(NbtOps.INSTANCE,data.get()).getOrThrow(),chunk.getSections()[index].getBiomes());
					}
					chunk.initializeLightSources(); chunks.put(pos,chunk); saved.put(pos,tag);
				}
			}
			if (chunks.size() != 162) throw new IllegalStateException("complete two-owner halo required");
			var source = new Source(chunks); var engine = new LevelLightEngine(source,true,true);
			var ordered = chunks.values().stream().sorted(java.util.Comparator.comparingInt((ProtoChunk c) -> c.getPos().x()).thenComparingInt(c -> c.getPos().z())).toList();
			for (var chunk : ordered) for (int i = 0; i < HEIGHT/16; i++) engine.updateSectionStatus(SectionPos.of(chunk.getPos(),i-4),chunk.getSections()[i].hasOnlyAir());
			drain(engine);
			for (var chunk : ordered) engine.setLightEnabled(chunk.getPos(),true);
			drain(engine);
			for (var chunk : ordered) engine.propagateLightSources(chunk.getPos());
			drain(engine);
			long blockDifferences = 0, skyDifferences = 0, values = 0; int changedChunks = 0;
			var examples = new ArrayList<Map<String,Object>>(); var mutable = new BlockPos.MutableBlockPos();
			for (var center : centers) for (int z = -TARGET_RADIUS; z <= TARGET_RADIUS; z++) for (int x = -TARGET_RADIUS; x <= TARGET_RADIUS; x++) {
				var pos = new ChunkPos(center.x()+x,center.z()+z);
				var allocatedSky = java.util.stream.IntStream.rangeClosed(-5,20).filter(y -> engine.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(pos,y)) != null).boxed().toList();
				var view = new SavedLightView263(source,pos,saved.get(pos),allocatedSky); boolean changed = false;
				for (int y = MIN_Y-16; y < MIN_Y+HEIGHT+16; y++) for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
					mutable.set(pos.getMinBlockX()+dx,y,pos.getMinBlockZ()+dz);
					for (var layer : List.of(LightLayer.BLOCK,LightLayer.SKY)) {
						int actual = layer == LightLayer.BLOCK ? view.block(mutable) : view.sky(mutable);
						int expected = engine.getLayerListener(layer).getLightValue(mutable); values++;
						if (actual != expected) {
							changed = true; if (layer == LightLayer.BLOCK) blockDifferences++; else skyDifferences++;
							if (examples.size() < 20) examples.add(Map.of("chunk",pos.x()+","+pos.z(),"x",mutable.getX(),"y",y,"z",mutable.getZ(),"layer",layer.name(),"saved",actual,"recomputed",expected));
						}
					}
				}
				if (changed) changedChunks++;
			}
			boolean equal = blockDifferences == 0 && skyDifferences == 0; success &= equal;
			var report = new TreeMap<String,Object>(); report.put("case",entry.getKey()); report.put("success",equal);
			report.put("halo_chunks",chunks.size()); report.put("required_chunks",50); report.put("compared_values",values);
			report.put("changed_chunks",changedChunks); report.put("block_differences",blockDifferences); report.put("sky_differences",skyDifferences); report.put("examples",examples); reports.add(report);
			System.out.println("SAVED_LIGHTING_CASE name="+entry.getKey()+" success="+equal+" changed_chunks="+changedChunks);
		}
		var result = Map.of("schema","worldgen-assist.saved-lighting-invariant.v1","success",success,"cases",reports,
			"scope","Original game lighting recomputed from each world's own 162 saved FULL halo chunks; every sky/block value including exterior sections in 50 interior FULL chunks. Saved original storage reads, no repair; not cross-world feature equality or general gameplay proof.");
		Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(result)+System.lineSeparator());
		if (!success) throw new IllegalStateException("saved lighting differs from original recomputation: "+output);
	}
	private static void drain(LevelLightEngine engine) {
		int passes = 0;
		do { engine.runLightUpdates(); if (++passes > 1000) throw new IllegalStateException("private lighting did not quiesce"); } while (engine.hasLightWork());
	}
	private static PalettedContainerFactory containers() {
		var biomes = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME); var ids = new IdMapper<Holder<Biome>>(); biomes.listElements().forEach(ids::add);
		var blocks = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY); var strategy = Strategy.createForBiomes(ids); var plains = biomes.getOrThrow(Biomes.PLAINS);
		return new PalettedContainerFactory(blocks,Blocks.AIR.defaultBlockState(),PalettedContainer.codecRW(BlockState.CODEC,blocks,Blocks.AIR.defaultBlockState()),
			strategy,plains,PalettedContainer.codecRO(Biome.CODEC,strategy,plains));
	}
	private static final class Source implements LightChunkGetter,BlockGetter {
		private final Map<ChunkPos,ProtoChunk> chunks;
		Source(Map<ChunkPos,ProtoChunk> chunks) { this.chunks = chunks; }
		@Override public LightChunk getChunkForLighting(int x, int z) { return chunks.get(new ChunkPos(x,z)); }
		@Override public BlockGetter getLevel() { return this; }
		@Override public int getMinY() { return MIN_Y; }
		@Override public int getHeight() { return HEIGHT; }
		@Override public BlockState getBlockState(BlockPos pos) {
			var chunk = chunks.get(ChunkPos.containing(pos)); return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
		}
		@Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
		@Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
	}
	private static final class Reader implements AutoCloseable {
		private final Path directory; private final Map<ChunkPos,RegionFile> regions = new HashMap<>();
		Reader(Path directory) { this.directory = directory; }
		CompoundTag tag(ChunkPos pos) throws Exception {
			var key = new ChunkPos(pos.getRegionX(),pos.getRegionZ()); var region = regions.get(key);
			if (region == null) {
				var path = directory.resolve("r."+key.x()+"."+key.z()+".mca");
				if (!Files.isRegularFile(path) || Files.size(path) > 128L*1024*1024) throw new IllegalArgumentException("retained region missing or too large");
				region = new RegionFile(new RegionStorageInfo("worldgen_assist_offline_lighting",Level.OVERWORLD,"chunk"),path,directory,false); regions.put(key,region);
			}
			try (var input = region.getChunkDataInputStream(pos)) {
				if (input == null) throw new IllegalArgumentException("missing saved halo chunk "+pos);
				var tag = NbtIo.read(input,NbtAccounter.create(16L*1024*1024));
				if (!tag.getString("Status").orElseThrow().equals("minecraft:full") || tag.getInt("xPos").orElseThrow() != pos.x() || tag.getInt("zPos").orElseThrow() != pos.z())
					throw new IllegalArgumentException("saved FULL halo identity mismatch");
				return tag;
			}
		}
		@Override public void close() throws Exception { for (var region : regions.values()) region.close(); }
	}
}
