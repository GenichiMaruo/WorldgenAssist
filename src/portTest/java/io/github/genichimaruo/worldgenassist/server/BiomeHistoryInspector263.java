package io.github.genichimaruo.worldgenassist.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import io.github.genichimaruo.worldgenassist.common.TerrainBiomeWindow;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Narrow hypothesis probe: original biome creation only, no terrain, saved world or production hooks. */
public final class BiomeHistoryInspector263 {
	private BiomeHistoryInspector263() { }
	public static void main(String[] args) throws Exception {
		if (args.length != 4 && args.length != 5) throw new IllegalArgumentException("new owned output,center x/z,history profile and optional saved region required");
		int centerX = Integer.parseInt(args[1]), centerZ = Integer.parseInt(args[2]);
		boolean interleave = args[3].equals("cache-carvers");
		boolean fitness = args[3].equals("tie-fitness");
		if (!interleave && !fitness && !args[3].equals("plain")) throw new IllegalArgumentException("known history profile required");
		if (!((centerX == -2012 && centerZ == 3041) || (centerX == -2051 && centerZ == 3027)
			|| (centerX == -2013 && centerZ == 3043))) throw new IllegalArgumentException("known public failure coordinate required");
		Path base = Path.of("test-artifacts").toRealPath(); Path output = Path.of(args[0]).toAbsolutePath().normalize();
		if (!output.startsWith(base) || output.equals(base) || Files.exists(output)) throw new IllegalArgumentException("new evidence child required");
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		var ties = new Climate.ParameterList<>(List.of(
			Pair.of(Climate.parameters(-1,0,0,0,0,0,0),"west"),Pair.of(Climate.parameters(1,0,0,0,0,0,0),"east")));
		ties.findValue(Climate.target(-1,0,0,0,0,0)); String fromWest = ties.findValue(Climate.target(0,0,0,0,0,0));
		ties.findValue(Climate.target(1,0,0,0,0,0)); String fromEast = ties.findValue(Climate.target(0,0,0,0,0,0));
		if (fromWest.equals(fromEast)) throw new IllegalStateException("Original history-sensitive tie witness missing");
		var registries = VanillaRegistries.createWorldLookup(); var biomes = registries.lookupOrThrow(Registries.BIOME);
		var ids = new IdMapper<Holder<Biome>>(); biomes.listElements().forEach(ids::add);
		var blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY); var biomeStrategy = Strategy.createForBiomes(ids); var plains = biomes.getOrThrow(Biomes.PLAINS);
		var containers = new PalettedContainerFactory(blockStrategy,Blocks.AIR.defaultBlockState(),PalettedContainer.codecRW(BlockState.CODEC,blockStrategy,Blocks.AIR.defaultBlockState()),
			biomeStrategy,plains,PalettedContainer.codecRO(Biome.CODEC,biomeStrategy,plains));
		var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		var presets = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
		var method = ChunkGenerator.class.getDeclaredMethod("doCreateBiomes",Blender.class,RandomState.class,ChunkAccess.class); method.setAccessible(true);
		var coordinates = new ArrayList<ChunkPos>(); for (int z = centerZ-1; z <= centerZ+1; z++) for (int x = centerX-1; x <= centerX+1; x++) coordinates.add(new ChunkPos(x,z));
		Map<ChunkPos,ProtoChunk> saved = null;
		if (args.length == 5 && !args[4].isBlank()) {
			Path directory = Path.of(args[4]).toRealPath();
			if (!directory.startsWith(base) || centerX != -2013 || centerZ != 3043)
				throw new IllegalArgumentException("owned saved public dev22 failure window required");
			saved = new HashMap<>();
			var biomeCodec = PalettedContainer.codecRW(Biome.CODEC,biomeStrategy,plains);
			var ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,registries);
			try (var storage = new net.minecraft.world.level.chunk.storage.RegionFileStorage(
				new net.minecraft.world.level.chunk.storage.RegionStorageInfo("worldgen_assist_offline_biomes",net.minecraft.world.level.Level.OVERWORLD,"chunk"),directory,false)) {
				for (var pos : coordinates) {
					var tag = java.util.Objects.requireNonNull(storage.read(pos),"missing saved biome chunk");
					if (!tag.getString("Status").orElseThrow().equals("minecraft:full")
						|| tag.getInt("xPos").orElseThrow() != pos.x() || tag.getInt("zPos").orElseThrow() != pos.z())
						throw new IllegalArgumentException("saved FULL biome coordinate differs");
					var chunk = new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),containers,null);
					var sections = new java.util.HashSet<Integer>();
					for (var value : tag.getListOrEmpty("sections")) {
						var section = (net.minecraft.nbt.CompoundTag)value;
						int index = section.getByte("Y").orElseThrow() + 4;
						if (index < 0 || index >= 24) continue;
						if (!sections.add(index)) throw new IllegalArgumentException("duplicate saved biome section");
						chunk.getSections()[index] = new net.minecraft.world.level.chunk.LevelChunkSection(
							chunk.getSection(index).getStates(),biomeCodec.parse(ops,section.getCompound("biomes").orElseThrow()).getOrThrow());
					}
					if (sections.size() != 24) throw new IllegalArgumentException("all saved biome sections required");
					chunk.setPersistedStatus(ChunkStatus.BIOMES); saved.put(pos,chunk);
				}
			}
		}
		if (fitness) {
			if (saved == null) throw new IllegalArgumentException("actual saved failure window required for fitness probe");
			// Exact original26.3 MultiNoiseBiomeSource chunk-volume framing, then original samplers and fitness.
			var state = RandomState.create(registries.lookupOrThrow(Registries.NOISE),8675309,settings.value());
			var bufferPool = state.acquireDensityBufferPool();
			Climate.TargetPoint target;
			try {
				var sampler = state.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.builder().enableCaches().useBufferArena(bufferPool).build());
				var volume = new net.minecraft.world.level.levelgen.densityfunction.DensityVolume(4,96,4,-32192,-64,48672,4,4,4);
				var values = List.of(sampler.temperature(),sampler.humidity(),sampler.continentalness(),sampler.erosion(),sampler.depth(),sampler.weirdness());
				float[] firstValues = new float[6];
				for(int i=0;i<6;i++) {
					var buffer=net.minecraft.world.level.levelgen.densityfunction.DensityBuffer.createUnpooled(volume.size());
					values.get(i).sampleVolume(buffer,volume);firstValues[i]=buffer.get(volume.indexUnchecked(0,0,0));
				}
				target=Climate.target(firstValues[0],firstValues[1],firstValues[2],firstValues[3],firstValues[4],firstValues[5]);
			} finally {state.releaseDensityBufferPool(bufferPool);}
			var bestByBiome = new java.util.TreeMap<String,Long>();
			for(var parameter:presets.value().parameters().values()) {
				String name=parameter.getSecond().unwrapKey().orElseThrow().identifier().toString();
				bestByBiome.merge(name,parameter.getFirst().fitness(target),Math::min);
			}
			long best=bestByBiome.values().stream().mapToLong(Long::longValue).min().orElseThrow();
			var winners=bestByBiome.entrySet().stream().filter(e->e.getValue()==best).map(Map.Entry::getKey).toList();
			String actual=saved.get(new ChunkPos(-2012,3042)).getNoiseBiome(-8048,-16,12168).unwrapKey().orElseThrow().identifier().toString();
			Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("success",true,"history_profile",args[3],
				"coordinate",List.of(-8048,-16,12168),"target",target,"minimum_fitness",best,"minimum_biomes",winners,"fitness_by_biome",bestByBiome,
				"saved_biome",actual,"scope","Exact original chunk-volume climate sampling and original ParameterPoint.fitness at the saved discrepancy. Not actual failed client payload or historical thread trace."))+System.lineSeparator());
			System.out.println("BIOME_FITNESS minimum="+best+" candidates="+winners+" saved="+actual);return;
		}
		var reports = new ArrayList<Map<String,Object>>(); Map<ChunkPos,ProtoChunk> first = null; byte[] firstHash = null;
		List<String> firstCarverBiomes = null; List<List<String>> firstCarvers = null;
		for (int history = 0; history < 4; history++) {
			var generator = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(presets),settings);
			var state = RandomState.create(registries.lookupOrThrow(Registries.NOISE),8675309,settings.value());
			// Real original biome calls, varying both previous regions and target order.
			int sign = history % 2 == 0 ? 1 : -1; int cx = 1512 + (history/2)*256, cz = -2512 - (history/2)*256;
			for (int i = 0; i < 4; i++) {
				var warm = new ProtoChunk(new ChunkPos(sign*(cx+i),sign*cz),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),containers,null);
				method.invoke(generator,Blender.empty(),state,warm);
			}
			var order = new ArrayList<>(coordinates); if (history % 2 == 1) Collections.reverse(order);
			var chunks = new HashMap<ChunkPos,ProtoChunk>();
			if (interleave) {
				// Model private cache hits and source-verified carver biome queries between jobs.
				// No terrain or carve operation is copied or executed.
				var jobs = new ArrayList<ChunkPos>();
				for(int outer=-4;outer<=4;outer++) for(int inner=-4;inner<=4;inner++) jobs.add(new ChunkPos(centerX+(history<2?inner:outer),centerZ+(history<2?outer:inner)));
				if(history%2==1) Collections.reverse(jobs);
				for(var job:jobs) {
					for(int z=job.z()-1;z<=job.z()+1;z++) for(int x=job.x()-1;x<=job.x()+1;x++) {
						var pos=new ChunkPos(x,z); if(chunks.containsKey(pos)) continue;
						var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),containers,null);
						method.invoke(generator,Blender.empty(),state,chunk); chunk.setPersistedStatus(ChunkStatus.BIOMES); chunks.put(pos,chunk);
					}
					var resolver=generator.getBiomeSource().createUncachedResolver(state);
					for(int dx=-8;dx<=8;dx++) for(int dz=-8;dz<=8;dz++) resolver.getNoiseBiome((job.x()+dx)*4,0,(job.z()+dz)*4);
				}
			} else for (var pos : order) {
				var chunk = new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),containers,null);
				method.invoke(generator,Blender.empty(),state,chunk); chunk.setPersistedStatus(ChunkStatus.BIOMES); chunks.put(pos,chunk);
			}
			byte[] hash = TerrainBiomeWindow.digest(centerX,centerZ,-64,384,(x,z) -> chunks.get(new ChunkPos(x,z)));
			var carverResolver = generator.getBiomeSource().createUncachedResolver(state);
			var carverBiomes = new ArrayList<String>(); var carvers = new ArrayList<List<String>>();
			for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
				var biome = carverResolver.getNoiseBiome((centerX+dx)*4,0,(centerZ+dz)*4);
				carverBiomes.add(biome.unwrapKey().orElseThrow().identifier().toString());
				var entries = new ArrayList<String>();
				for (var carver : generator.getBiomeGenerationSettings(biome).getCarvers()) entries.add(carver.unwrapKey().orElseThrow().identifier().toString());
				carvers.add(entries);
			}
			if (firstCarvers == null) { firstCarvers = carvers; firstCarverBiomes = carverBiomes; }
			int carverBiomeChanges = 0, carverInputChanges = 0;
			for (int i = 0; i < 289; i++) { if (!carverBiomes.get(i).equals(firstCarverBiomes.get(i))) carverBiomeChanges++; if (!carvers.get(i).equals(firstCarvers.get(i))) carverInputChanges++; }
			long changes = 0; var examples = new ArrayList<Map<String,Object>>();
			if (first == null) { first = chunks; firstHash = hash; }
			else for (var pos : coordinates) for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) for (int qy = -16; qy < 80; qy++) {
				int x = pos.x()*4+qx, z = pos.z()*4+qz; var a = first.get(pos).getNoiseBiome(x,qy,z); var b = chunks.get(pos).getNoiseBiome(x,qy,z);
				if (!a.equals(b)) {
					changes++; if (examples.size() < 8) examples.add(Map.of("quart_x",x,"quart_y",qy,"quart_z",z,"first",a.unwrapKey().orElseThrow().identifier().toString(),"other",b.unwrapKey().orElseThrow().identifier().toString()));
				}
			}
			var report = new java.util.LinkedHashMap<String,Object>(Map.<String,Object>of("history",history,"order",(history<2?"zx":"xz")+(history % 2 == 0 ? "-forward" : "-reverse"),"biome_digest",HexFormat.of().formatHex(hash),
				"changed_voxels",changes,"digest_equal",java.util.Arrays.equals(hash,firstHash),"examples",examples,
				"changed_carver_biomes",carverBiomeChanges,"changed_ordered_carver_inputs",carverInputChanges,"canonical_biome_chunks",chunks.size()));
			if (saved != null) {
				long savedChanges = 0; var savedExamples = new ArrayList<Map<String,Object>>();
				for (var pos : coordinates) for (int qz=0;qz<4;qz++) for (int qx=0;qx<4;qx++) for (int qy=-16;qy<80;qy++) {
					int x=pos.x()*4+qx,z=pos.z()*4+qz;
					String a=saved.get(pos).getNoiseBiome(x,qy,z).unwrapKey().orElseThrow().identifier().toString();
					String b=chunks.get(pos).getNoiseBiome(x,qy,z).unwrapKey().orElseThrow().identifier().toString();
					if (!a.equals(b)) { savedChanges++; if(savedExamples.size()<8) savedExamples.add(Map.of("quart_x",x,"quart_y",qy,"quart_z",z,"saved",a,"generated",b)); }
				}
				report.put("saved_changed_voxels",savedChanges);report.put("saved_examples",savedExamples);
				// Saved codec may compact unused palette entries: voxel comparison is independent of runtime digest framing.
				report.put("saved_scope","All13824 original-code-decoded saved quart values; not live failed response or unused runtime palette proof");
			}
			reports.add(report); System.out.println("BIOME_HISTORY history="+history+" changed_voxels="+changes+" digest_equal="+java.util.Arrays.equals(hash,firstHash));
		}
		Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("schema","worldgen-assist.biome-history-probe.v1","success",true,"history_profile",args[3],
			"synthetic_tie",Map.of("warm_west",fromWest,"warm_east",fromEast),"center",centerX+","+centerZ,"public_seed",8675309,"target_quart_voxels_per_history",13824,"histories",reports,
			"scope",interleave ? "Original doCreateBiomes,4 histories x81 job centers/121 cache chunks plus4 warm chunks/history,interleaved original17x17 quartY0 carver biome query order. Compare all13824 target voxels/digest and final289 ordered carver inputs. No terrain,actual carve,features,production hooks or saved world. History witness is not actual failed payload proof." : "Original doCreateBiomes via reflection,4 histories x9 target chunks plus4 warm chunks/history, then exact original17x17 quartY0 carver biome query order and registry-id lists. No terrain/features, no private-computer mutation or saved world. A witness at this window suggests but does not prove the failed peer payload cause."))+System.lineSeparator());
	}
}
