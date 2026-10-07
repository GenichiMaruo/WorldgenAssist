package io.github.genichimaruo.worldgenassist.server;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.material.FluidState;

/** Read one stopped B-center region; original storage/codecs and stored heightmap. */
public final class GameplaySiteInspector263 {
    private static final Set<String> GROUND=Set.of("minecraft:grass_block","minecraft:dirt","minecraft:coarse_dirt","minecraft:rooted_dirt","minecraft:stone","minecraft:andesite","minecraft:diorite","minecraft:granite","minecraft:deepslate","minecraft:sandstone","minecraft:red_sandstone","minecraft:sand","minecraft:red_sand","minecraft:gravel");
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("one descriptor required");SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        Path base=Path.of("test-artifacts").toRealPath(),descriptor=Path.of(args[0]).toRealPath();if(!descriptor.startsWith(base))throw new IllegalArgumentException("owned descriptor required");
        var d=JsonParser.parseString(Files.readString(descriptor)).getAsJsonObject();Path region=Path.of(d.get("region").getAsString()).toRealPath(),output=Path.of(d.get("output").getAsString()).toAbsolutePath().normalize();
        if(!region.startsWith(base) || !region.getFileName().toString().equals("r.-48.78.mca") || Files.size(region)>128L*1024*1024 || !output.startsWith(base) || output.equals(base) || Files.exists(output))throw new IllegalArgumentException("exact owned B-region/new output required");
        Set<String> received=new java.util.HashSet<>();for(var v:d.getAsJsonArray("received"))received.add(v.getAsString());if(received.size()!=3461)throw new IllegalArgumentException("exact B view receipt scope required");
        Path working=Path.of(d.get("reader_copy").getAsString()).toAbsolutePath().normalize();
        if(!working.startsWith(base) || working.equals(region) || !working.getFileName().equals(region.getFileName()) || Files.exists(working))throw new IllegalArgumentException("new owned reader copy required");
        Files.createDirectories(working.getParent());Files.copy(region,working);
        byte[] original=Files.readAllBytes(region);if(!java.util.Arrays.equals(original,Files.readAllBytes(working)))throw new IllegalArgumentException("reader copy differs");
        var histogram=new TreeMap<String,Long>();var missing=new ArrayList<String>();int full=0;long columns=0,patches=0;Map<String,Object> nearest=null;int nearestRadius=Integer.MAX_VALUE;
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        try(var reader=new RegionFile(new RegionStorageInfo("worldgen_assist_offline_site",Level.OVERWORLD,"chunk"),working,working.getParent(),false)) {
            for(int cx=-1536;cx< -1504;cx++)for(int cz=2496;cz<2528;cz++) {
                var pos=new ChunkPos(cx,cz);String key=cx+","+cz;if(!received.contains(key))continue;
                try(var input=reader.getChunkDataInputStream(pos)) {
                    if(input==null){missing.add(key);continue;}var tag=NbtIo.read(input,NbtAccounter.create(16L*1024*1024));
                    if(tag.getInt("xPos").orElseThrow()!=cx || tag.getInt("zPos").orElseThrow()!=cz)throw new IllegalArgumentException("saved coordinate identity");
                    if(!tag.getString("Status").orElseThrow().equals("minecraft:full")){missing.add(key);continue;}full++;
                    var sections=new HashMap<Integer,PalettedContainer<BlockState>>();for(var e:tag.getListOrEmpty("sections")){var s=(CompoundTag)e;int sy=s.getByte("Y").orElseThrow();if(sy< -4 || sy>19)continue;var states=s.getCompound("block_states");if(states.isPresent() && sections.put(sy,codec.parse(NbtOps.INSTANCE,states.get()).getOrThrow())!=null)throw new IllegalArgumentException("duplicate section");}
                    var view=new View(cx,cz,sections);var heights=new SimpleBitStorage(Mth.ceillog2(384+1),256,tag.getCompound("Heightmaps").orElseThrow().getLongArray("MOTION_BLOCKING").orElseThrow());
                    for(int x=0;x<15;x++)for(int z=0;z<16;z++) {
                        int y=heights.get(x+z*16)-64;columns++;var feet=new BlockPos(cx*16+x,y,cz*16+z);var floor=feet.below();var target=floor.east();var support=target.below();
                        var a=view.getBlockState(floor);var b=view.getBlockState(target);var c=view.getBlockState(support);String id=BuiltInRegistries.BLOCK.getKey(a.getBlock()).toString();histogram.merge(id,1L,Long::sum);
                        if(y< -60 || y>315 || !GROUND.contains(id) || !GROUND.contains(BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString()) || !a.isCollisionShapeFullBlock(view,floor) || !b.isCollisionShapeFullBlock(view,target) || !c.isCollisionShapeFullBlock(view,support) || !a.getFluidState().isEmpty() || !b.getFluidState().isEmpty() || !c.getFluidState().isEmpty() || !view.getBlockState(feet).isAir() || !view.getBlockState(feet.above()).isAir() || !view.getBlockState(feet.above(2)).isAir() || !view.getBlockState(target.above()).isAir())continue;
                        patches++;int radius=Math.max(Math.abs(feet.getX()+24192),Math.abs(feet.getZ()-40192));if(radius<nearestRadius){nearestRadius=radius;nearest=new LinkedHashMap<>();nearest.put("stand",new int[]{feet.getX(),y,feet.getZ()});nearest.put("target",new int[]{target.getX(),target.getY(),target.getZ()});nearest.put("radius",radius);nearest.put("stand_block",id);nearest.put("target_block",BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString());nearest.put("support_block",BuiltInRegistries.BLOCK.getKey(c.getBlock()).toString());nearest.put("chunk",key);}
                    }
                }
            }
        }
        byte[] closed=Files.readAllBytes(working);int expected=((original.length+4095)/4096)*4096;
        if(closed.length!=expected || !java.util.Arrays.equals(original,Files.readAllBytes(region)))throw new IllegalStateException("raw input or reader copy close size changed unexpectedly");
        for(int i=0;i<closed.length;i++)if(closed[i]!=(i<original.length?original[i]:0))throw new IllegalStateException("reader changed chunk bytes; only original close padding allowed");
        var report=new LinkedHashMap<String,Object>();report.put("success",true);report.put("full_chunks",full);report.put("missing_or_not_full",missing);report.put("columns",columns);report.put("floor_histogram",histogram);report.put("dry_supported_patches",patches);report.put("nearest",nearest);report.put("reader_close_padding_bytes",closed.length-original.length);report.put("immutable_raw_preserved",true);report.put("scope","One original stopped B-center region within actual measured receipts. Original codec/heightmap/collision shapes on a disposable byte-identical reader copy; original close may add only zero EOF padding. Immutable raw unchanged. Missing/nonFULL saves reported; no all-view or ordinary gameplay safety claim.");Files.writeString(output,new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report));
    }
    private record View(int cx,int cz,Map<Integer,PalettedContainer<BlockState>> sections)implements BlockGetter {
        @Override public int getMinY(){return -64;}@Override public int getHeight(){return 384;}
        @Override public BlockState getBlockState(BlockPos p){if((p.getX()>>4)!=cx || (p.getZ()>>4)!=cz)return Blocks.AIR.defaultBlockState();var states=sections.get(p.getY()>>4);return states==null?Blocks.AIR.defaultBlockState():states.get(p.getX()&15,p.getY()&15,p.getZ()&15);}
        @Override public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}@Override public BlockEntity getBlockEntity(BlockPos p){return null;}
    }
}
