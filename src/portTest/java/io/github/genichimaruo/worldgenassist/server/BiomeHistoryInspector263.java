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
		if (args.length != 4) throw new IllegalArgumentException("new owned output,center x/z and history profile required");
		int centerX = Integer.parseInt(args[1]), centerZ = Integer.parseInt(args[2]);
		boolean interleave = args[3].equals("cache-carvers");
		if (!interleave && !args[3].equals("plain")) throw new IllegalArgumentException("known history profile required");
		if (!((centerX == -2012 && centerZ == 3041) || (centerX == -2051 && centerZ == 3027))) throw new IllegalArgumentException("known public failure coordinate required");
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
			var report = Map.<String,Object>of("history",history,"order",(history<2?"zx":"xz")+(history % 2 == 0 ? "-forward" : "-reverse"),"biome_digest",HexFormat.of().formatHex(hash),
				"changed_voxels",changes,"digest_equal",java.util.Arrays.equals(hash,firstHash),"examples",examples,
				"changed_carver_biomes",carverBiomeChanges,"changed_ordered_carver_inputs",carverInputChanges,"canonical_biome_chunks",chunks.size());
			reports.add(report); System.out.println("BIOME_HISTORY history="+history+" changed_voxels="+changes+" digest_equal="+java.util.Arrays.equals(hash,firstHash));
		}
		Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("schema","worldgen-assist.biome-history-probe.v1","success",true,"history_profile",args[3],
			"synthetic_tie",Map.of("warm_west",fromWest,"warm_east",fromEast),"center",centerX+","+centerZ,"public_seed",8675309,"target_quart_voxels_per_history",13824,"histories",reports,
			"scope",interleave ? "Original doCreateBiomes,4 histories x81 job centers/121 cache chunks plus4 warm chunks/history,interleaved original17x17 quartY0 carver biome query order. Compare all13824 target voxels/digest and final289 ordered carver inputs. No terrain,actual carve,features,production hooks or saved world. History witness is not actual failed payload proof." : "Original doCreateBiomes via reflection,4 histories x9 target chunks plus4 warm chunks/history, then exact original17x17 quartY0 carver biome query order and registry-id lists. No terrain/features, no private-computer mutation or saved world. A witness at this window suggests but does not prove the failed peer payload cause."))+System.lineSeparator());
	}
}
