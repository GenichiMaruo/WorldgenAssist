package io.github.genichimaruo.worldgenassist.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.function.BiFunction;
import net.minecraft.world.level.chunk.ChunkAccess;
import com.google.gson.GsonBuilder;

/** Explicit diagnostic only, bounded final public region on the client worker. */
public final class PublicPeerProbe {
	private final boolean enabled;
	private final Path directory;
	private final int maxPairs;
	private final long maxBytes;
	private int admitted;
	private long reservedBytes;
	public PublicPeerProbe(boolean enabled, Path directory) { this(enabled,directory,4096,512L*1024*1024); }
	PublicPeerProbe(boolean enabled, Path directory, int maxPairs, long maxBytes) {
		if (maxPairs < 1 || maxPairs > 4096 || maxBytes < 1 || maxBytes > 512L*1024*1024) throw new IllegalArgumentException("Invalid diagnostic bounds");
		this.enabled = enabled; this.directory = directory.toAbsolutePath().normalize(); this.maxPairs = maxPairs; this.maxBytes = maxBytes;
	}
	public void capture(TerrainDensityJob job, CompleteTerrainData data, BiFunction<Integer,Integer,ChunkAccess> chunks) throws IOException {
		if (!enabled || job.worldSeed() != 8675309 || job.workKind() != TerrainWorkKind.COMPLETE_TERRAIN
			|| job.identity().chunkX() < -2064 || job.identity().chunkX() > -1984
			|| job.identity().chunkZ() < 2984 || job.identity().chunkZ() > 3064
			|| !job.identity().dimension().toString().equals("minecraft:overworld")) return;
		synchronized (this) { if (admitted >= maxPairs || reservedBytes >= maxBytes) return; }
		if (data.minY() != job.minY() || data.height() != job.height()) throw new IllegalArgumentException("probe result geometry differs");
		String name = job.identity().jobId().toString();
		var metadata = new LinkedHashMap<String,Object>();
		metadata.put("schema","worldgen-assist.public-peer-probe.v2"); metadata.put("job_id",name);
		metadata.put("protocol",job.identity().protocolVersion().value()); metadata.put("dimension",job.identity().dimension().toString());
		metadata.put("x",job.identity().chunkX()); metadata.put("z",job.identity().chunkZ()); metadata.put("public_seed",job.worldSeed());
		metadata.put("generate_structures",job.generateStructures()); metadata.put("noise_settings",job.noiseSettings().toString());
		metadata.put("context",job.identity().contextFingerprint().toHex()); metadata.put("min_y",job.minY()); metadata.put("height",job.height());
		metadata.put("cell_width",job.cellWidth()); metadata.put("cell_height",job.cellHeight());
		metadata.put("shaping_base64",Base64.getEncoder().encodeToString(job.shaping().encode()));
		metadata.put("worker_thread_id",Thread.currentThread().threadId());
		// Capture original inputs before the private worker can reuse its cache.
		var possible = new java.util.HashSet<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>();
		int cx=job.identity().chunkX(), cz=job.identity().chunkZ();
		for (int z=cz-1;z<=cz+1;z++) for(int x=cx-1;x<=cx+1;x++) chunks.apply(x,z).collectBiomesInPalette(possible);
		var names=possible.stream().map(b -> b.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
		if (names.isEmpty() || names.size()>65535) throw new IllegalArgumentException("Invalid diagnostic palette");
		var codes=new java.util.HashMap<String,Integer>(); for(int i=0;i<names.size();i++) codes.put(names.get(i),i);
		byte[] samples=new byte[9*16*(job.height()/4)*2]; int cursor=0;
		for(int z=cz-1;z<=cz+1;z++) for(int x=cx-1;x<=cx+1;x++) {
			ChunkAccess chunk=chunks.apply(x,z);
			if(chunk.getPos().x()!=x || chunk.getPos().z()!=z || chunk.getMinY()!=job.minY() || chunk.getHeight()!=job.height()) throw new IllegalArgumentException("Wrong diagnostic chunk");
			for(int qz=0;qz<4;qz++) for(int qx=0;qx<4;qx++) for(int y=job.minY()/4;y<(job.minY()+job.height())/4;y++) {
				int code=codes.get(chunk.getNoiseBiome(x*4+qx,y,z*4+qz).unwrapKey().orElseThrow().identifier().toString());
				samples[cursor++]=(byte)(code>>>8); samples[cursor++]=(byte)code;
			}
		}
		metadata.put("biome_names",names); metadata.put("biome_samples_base64",Base64.getEncoder().encodeToString(samples));
		metadata.put("biome_digest_hex",java.util.HexFormat.of().formatHex(data.biomeWindowDigest()));
		byte[] body=data.encode(), json=new GsonBuilder().setPrettyPrinting().create().toJson(metadata).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		synchronized(this) {
			if(admitted>=maxPairs || body.length+json.length>maxBytes-reservedBytes) return;
			admitted++; reservedBytes+=body.length+json.length;
		}
		Files.createDirectories(directory);
		if (Files.isSymbolicLink(directory)) throw new IOException("Probe directory must not be symbolic link");
		Files.write(directory.resolve(name+".bin"),body,StandardOpenOption.CREATE_NEW);
		Files.write(directory.resolve(name+".json"),json,StandardOpenOption.CREATE_NEW);
	}
}
