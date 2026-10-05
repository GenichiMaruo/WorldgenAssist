package io.github.genichimaruo.worldgenassist.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.genichimaruo.worldgenassist.common.CompleteTerrainData;

/** Offline exact captured inputs/results and real server-issued pair assignments. */
public final class PublicPeerCaptureInspector263 {
	private record Capture(int owner, Path metadata, Path body, JsonObject request, List<String> names, byte[] samples) { }
	private PublicPeerCaptureInspector263() { }
	public static void main(String[] args) throws Exception {
		if(args.length!=2) throw new IllegalArgumentException("owned case and new output required");
		Path base=Path.of("test-artifacts").toRealPath(), root=Path.of(args[0]).toRealPath(), output=Path.of(args[1]).toAbsolutePath().normalize();
		if(!root.startsWith(base) || root.equals(base) || !output.startsWith(base) || Files.exists(output)) throw new IllegalArgumentException("owned paths required");
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		Map<UUID,Capture> captures=new HashMap<>(); var identities=new ArrayList<Map<String,Object>>();
		for(int owner=0;owner<2;owner++) {
			Path directory=root.resolve("clients/owner-"+owner+"/client/worldgen-assist-peer-probe").toRealPath();
			if(!directory.startsWith(root)) throw new IllegalArgumentException("capture escapes case");
			List<Path> files; try(var stream=Files.list(directory)) { files=stream.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList(); }
			if(files.isEmpty() || files.size()>4096) throw new IllegalArgumentException("capture count bound");
			try(var stream=Files.list(directory)) { if(stream.filter(p->p.getFileName().toString().endsWith(".bin")).count()!=files.size()) throw new IllegalArgumentException("partial capture pairs"); }
			long totalBytes=0;
			for(Path file:files) {
				if(Files.isSymbolicLink(file) || Files.size(file)>262144) throw new IllegalArgumentException("metadata bound");
				JsonObject meta=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				UUID id=UUID.fromString(meta.get("job_id").getAsString());
				int x=meta.get("x").getAsInt(), z=meta.get("z").getAsInt();
				if(!file.getFileName().toString().equals(id+".json") || !meta.get("schema").getAsString().equals("worldgen-assist.public-peer-probe.v2")
					|| meta.get("public_seed").getAsLong()!=8675309 || !meta.get("dimension").getAsString().equals("minecraft:overworld")
					|| meta.get("protocol").getAsInt()!=12 || x< -2064 || x> -1984 || z<2984 || z>3064 || meta.get("min_y").getAsInt()!=-64 || meta.get("height").getAsInt()!=384) throw new IllegalArgumentException("public assignment differs");
				Path body=directory.resolve(id+".bin"); if(Files.isSymbolicLink(body) || Files.size(body)>CompleteTerrainData.MAX_RAW_BYTES) throw new IllegalArgumentException("body bound");
				totalBytes+=Files.size(file)+Files.size(body); if(totalBytes>512L*1024*1024) throw new IllegalArgumentException("capture byte bound");
				byte[] raw=Files.readAllBytes(body); var data=CompleteTerrainData.decode(raw,-64,384);
				var names=new ArrayList<String>(); for(var name:meta.getAsJsonArray("biome_names")) names.add(name.getAsString());
				if(names.isEmpty() || names.size()>65535) throw new IllegalArgumentException("palette bound");
				for(int i=0;i<names.size();i++) if(names.get(i).getBytes(StandardCharsets.UTF_8).length>256 || (i>0 && names.get(i-1).compareTo(names.get(i))>=0)) throw new IllegalArgumentException("palette ordering/framing");
				byte[] samples=Base64.getDecoder().decode(meta.get("biome_samples_base64").getAsString());
				if(samples.length!=9*16*96*2) throw new IllegalArgumentException("complete voxel window required");
				for(int i=0;i<samples.length;i+=2) if(code(samples,i)>=names.size()) throw new IllegalArgumentException("sample outside palette");
				MessageDigest hash=MessageDigest.getInstance("SHA-256");
				hash.update("worldgen_assist:terrain_biome_window_v2".getBytes(StandardCharsets.UTF_8));
				hash.update(ByteBuffer.allocate(16).putInt(x).putInt(z).putInt(-64).putInt(384).array()); hash.update(ByteBuffer.allocate(4).putInt(names.size()).array());
				for(String name:names) { byte[] bytes=name.getBytes(StandardCharsets.UTF_8); hash.update((byte)(bytes.length>>>8)); hash.update((byte)bytes.length); hash.update(bytes); }
				hash.update(samples); byte[] digest=hash.digest();
				if(!Arrays.equals(digest,data.biomeWindowDigest()) || !HexFormat.of().formatHex(digest).equals(meta.get("biome_digest_hex").getAsString())) throw new IllegalArgumentException("captured snapshot does not explain its exact returned digest: "+id);
				if(captures.put(id,new Capture(owner,file,body,meta,List.copyOf(names),samples))!=null) throw new IllegalArgumentException("duplicate capture identity");
				identities.add(Map.of("owner",owner,"job_id",id.toString(),"metadata",file.toString(),"metadata_sha256",sha(Files.readAllBytes(file)),"body",body.toString(),"body_sha256",sha(raw),"bytes",raw.length));
			}
		}
		var assignment=Pattern.compile("job\\.peer_terrain_sent id=([0-9a-f-]+) primary=([0-9a-f-]+) .*chunk=(-?\\d+),(-?\\d+)");
		var failure=Pattern.compile("job\\.peer_terrain_difference primary=([0-9a-f-]+) peer=([0-9a-f-]+) chunk=(-?\\d+),(-?\\d+)");
		var differences=new ArrayList<Map<String,Object>>(); var failedAssignments=new ArrayList<String>(); int paired=0;
		try(var lines=Files.lines(root.resolve("remote-evidence/latest.log"))) {
			for(String line:(Iterable<String>)lines::iterator) {
				var fail=failure.matcher(line); if(fail.find()) failedAssignments.add(fail.group(1)+"/"+fail.group(2));
				var match=assignment.matcher(line); if(!match.find()) continue;
				UUID primary=UUID.fromString(match.group(2)), peer=UUID.fromString(match.group(1));
				Capture a=captures.get(primary),b=captures.get(peer); if(a==null || b==null) continue;
				if(a.owner()==b.owner()) throw new IllegalArgumentException("captured peers are not independent owners");
				for(String field:List.of("protocol","dimension","x","z","public_seed","generate_structures","noise_settings","context","min_y","height","cell_width","cell_height","shaping_base64")) if(!a.request().get(field).equals(b.request().get(field))) throw new IllegalArgumentException("assigned request differs: "+field);
				if(a.request().get("x").getAsInt()!=Integer.parseInt(match.group(3)) || a.request().get("z").getAsInt()!=Integer.parseInt(match.group(4))) throw new IllegalArgumentException("server coordinate differs");
				paired++; var first=CompleteTerrainData.decode(Files.readAllBytes(a.body()),-64,384); var second=CompleteTerrainData.decode(Files.readAllBytes(b.body()),-64,384);
				if(!first.equals(second)) {
					long changed=0; var examples=new ArrayList<Map<String,Object>>();
					for(int i=0;i<a.samples().length/2;i++) {
						String av=a.names().get(code(a.samples(),i*2)),bv=b.names().get(code(b.samples(),i*2)); if(av.equals(bv)) continue;
						changed++; int horizontal=i/96,chunk=horizontal/16,quart=horizontal%16;
						if(examples.size()<16) examples.add(Map.of("quart_x",(Integer.parseInt(match.group(3))-1+chunk%3)*4+quart%4,"quart_y",-16+i%96,"quart_z",(Integer.parseInt(match.group(4))-1+chunk/3)*4+quart/4,"first",av,"second",bv));
					}
					differences.add(Map.of("primary",primary.toString(),"peer",peer.toString(),"coordinate",match.group(3)+","+match.group(4),"components",CompleteTerrainDifference.describe(first,second),"changed_biome_voxels",changed,"first_palette",a.names(),"second_palette",b.names(),"examples",examples));
				}
			}
		}
		if(paired==0) throw new IllegalArgumentException("no real complete captured peer assignment");
		var missingFailures=new ArrayList<String>(); for(String pair:failedAssignments) { String[] ids=pair.split("/"); if(!captures.containsKey(UUID.fromString(ids[0])) || !captures.containsKey(UUID.fromString(ids[1]))) missingFailures.add(pair); }
		Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("schema","worldgen-assist.public-peer-inspection.v1","success",true,"capture_count",captures.size(),"paired_count",paired,"captures",identities,"differences",differences,"runtime_failed_pairs_missing_capture",missingFailures,"scope","Exact assigned requests/original bounded body decode/all13824 biome voxels and unused palette names/digest framing/actual server peers. Diagnostic execution success is not parity or speed proof.")));
		System.out.println("PUBLIC_PEER_INSPECTION captures="+captures.size()+" paired="+paired+" differences="+differences.size()+" missing_failed="+missingFailures.size());
	}
	private static int code(byte[] samples,int offset) { return (Byte.toUnsignedInt(samples[offset])<<8)|Byte.toUnsignedInt(samples[offset+1]); }
	private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
}
