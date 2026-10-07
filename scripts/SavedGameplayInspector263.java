package io.github.genichimaruo.worldgenassist.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
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

/** Offline final survival/player and placed-voxel witness using original readers. */
public final class SavedGameplayInspector263 {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("one owned descriptor required");
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        Path base=Path.of("test-artifacts").toRealPath();
        var descriptor=JsonParser.parseString(Files.readString(owned(base,Path.of(args[0])))).getAsJsonObject();
        Path output=Path.of(descriptor.get("output").getAsString()).toAbsolutePath().normalize();
        if(!output.startsWith(base) || output.equals(base) || Files.exists(output))throw new IllegalArgumentException("new owned output required");
        var players=descriptor.getAsJsonArray("players");if(players.size()!=4)throw new IllegalArgumentException("two owners/two conditions required");
        var rows=new ArrayList<LinkedHashMap<String,Object>>();boolean success=true;
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        for(var entry:players) {
            var item=entry.getAsJsonObject();Path data=owned(base,Path.of(item.get("data").getAsString())),stats=owned(base,Path.of(item.get("stats").getAsString()));
            Path region=owned(base,Path.of(item.get("region").getAsString()));
            if(Files.size(data)>1048576 || Files.size(stats)>1048576 || Files.size(region)>128L*1024*1024)throw new IllegalArgumentException("saved input bound");
            String dataHash=hash(data),statsHash=hash(stats),regionHash=hash(region);
            var player=NbtIo.readCompressed(data,NbtAccounter.create(16L*1024*1024));var pos=player.getListOrEmpty("Pos");
            if(pos.size()!=3)throw new IllegalArgumentException("saved position missing");
            double x=pos.getDouble(0).orElseThrow(),y=pos.getDouble(1).orElseThrow(),z=pos.getDouble(2).orElseThrow();
            float health=player.getFloat("Health").orElseThrow();short death=player.getShort("DeathTime").orElseThrow();int mode=player.getInt("playerGameType").orElseThrow();
            boolean flying=player.getCompoundOrEmpty("abilities").getBoolean("flying").orElseThrow(),ground=player.getBoolean("OnGround").orElseThrow();
            var custom=JsonParser.parseString(Files.readString(stats)).getAsJsonObject().getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
            long deaths=counter(custom,"minecraft:deaths"),damage=counter(custom,"minecraft:damage_taken");
            var target=item.getAsJsonArray("target");if(target.size()!=3)throw new IllegalArgumentException("target geometry");
            int tx=target.get(0).getAsInt(),ty=target.get(1).getAsInt(),tz=target.get(2).getAsInt();
            if(Math.abs((long)tx)>30000000 || Math.abs((long)tz)>30000000 || ty< -64 || ty>=320)throw new IllegalArgumentException("target bounds");
            var chunk=new ChunkPos(tx>>4,tz>>4);
            if(!region.getFileName().toString().equals("r."+chunk.getRegionX()+"."+chunk.getRegionZ()+".mca"))throw new IllegalArgumentException("region identity");
            BlockState actual=null;
            try(var reader=new RegionFile(new RegionStorageInfo("worldgen_assist_offline_gameplay",Level.OVERWORLD,"chunk"),region,region.getParent(),false);var input=reader.getChunkDataInputStream(chunk)) {
                if(input==null)throw new IllegalArgumentException("missing target chunk");
                var tag=NbtIo.read(input,NbtAccounter.create(16L*1024*1024));
                if(!tag.getString("Status").orElseThrow().equals("minecraft:full") || tag.getInt("xPos").orElseThrow()!=chunk.x() || tag.getInt("zPos").orElseThrow()!=chunk.z())throw new IllegalArgumentException("saved FULL chunk identity");
                for(var sectionEntry:tag.getListOrEmpty("sections")) {
                    var section=(CompoundTag)sectionEntry;
                    if(section.getByte("Y").orElseThrow()==(ty>>4)) {
                        if(actual!=null)throw new IllegalArgumentException("duplicate target section");
                        actual=codec.parse(NbtOps.INSTANCE,section.getCompound("block_states").orElseThrow()).getOrThrow().get(tx&15,ty&15,tz&15);
                    }
                }
            }
            boolean safe=Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && x==item.get("x").getAsDouble() && y==item.get("y").getAsDouble() && z==item.get("z").getAsDouble()
                && y>=-64 && y<320 && health==20 && death==0 && mode==0 && ground && !flying && deaths==0 && damage==0 && actual!=null && actual.is(Blocks.COBBLESTONE);
            if(!dataHash.equals(hash(data)) || !statsHash.equals(hash(stats)) || !regionHash.equals(hash(region)))throw new IllegalStateException("saved input changed");
            var row=new LinkedHashMap<String,Object>();row.put("condition",item.get("condition").getAsString());row.put("owner",item.get("owner").getAsString());row.put("success",safe);
            row.put("position",new double[]{x,y,z});row.put("health",health);row.put("death_time",death);row.put("game_type",mode);row.put("on_ground",ground);row.put("flying",flying);
            row.put("recorded_deaths",deaths);row.put("recorded_damage_taken",damage);row.put("target",new int[]{tx,ty,tz});row.put("saved_target_block",actual==null?null:actual.toString());
            row.put("data_sha256",dataHash);row.put("stats_sha256",statsHash);row.put("region_sha256",regionHash);rows.add(row);success&=safe;
        }
        var report=new LinkedHashMap<String,Object>();report.put("schema","worldgen-assist.saved-gameplay.v1");report.put("success",success);report.put("players",rows);
        report.put("scope","Original bounded NBT/RegionFile/paletted codec after clean shutdown. Exact final survival landing state, recorded deaths/damage and the original client-placed COBBLESTONE voxel; not server restart or continuous movement proof.");
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report));if(!success)System.exit(1);
    }
    private static Path owned(Path base,Path path)throws Exception{Path p=path.toRealPath();if(!p.startsWith(base) || p.equals(base))throw new IllegalArgumentException("owned input required");return p;}
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static long counter(JsonObject custom,String key){return custom!=null && custom.has(key)?custom.get(key).getAsLong():0;}
}
