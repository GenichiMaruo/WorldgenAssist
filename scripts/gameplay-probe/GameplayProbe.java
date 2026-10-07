package io.github.genichimaruo.worldgenassist.probe;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Separate, unshipped input driver for isolated loopback evidence profiles. */
public final class GameplayProbe {
    private static final String NONCE=System.getenv("WORLDGEN_GAMEPLAY_PROBE_NONCE"),OWNER=System.getenv("WORLDGEN_GAMEPLAY_PROBE_OWNER");
    private static Path root;
    private static String phase="";
    private static int ticks,stable,miningCalls,visitedColumns,groundColumns;
    private static long started;
    private static boolean done,holding,actionStarted,reconnectStarted;
    private static long disconnectedAt;
    private static MultiPlayerGameMode activeMode;
    private static Object priorConnection;
    private static BlockPos stand,target;
    private static String standBlock,targetBlock,supportBlock;

    public static void tick(Minecraft client) {
        if(NONCE==null || !NONCE.matches("[a-f0-9]{32}") || OWNER==null || !OWNER.matches("ScenarioOwner[AB]"))return;
        try {
            if(root==null) {
                String configured=System.getenv("WORLDGEN_GAMEPLAY_PROBE_ROOT");if(configured==null)return;
                root=Path.of(configured).toRealPath();
                if(!root.startsWith(client.gameDirectory.toPath().toRealPath()) || !root.getFileName().toString().equals("gameplay-probe"))throw new IllegalStateException("owned profile required");
            }
            if(++ticks%5==0 && Files.exists(root.resolve("control.json"))) {
                Path control=root.resolve("control.json");if(Files.size(control)>4096)throw new IllegalStateException("control bound");
                var value=JsonParser.parseString(Files.readString(control)).getAsJsonObject();
                if(!NONCE.equals(value.get("nonce").getAsString()) || !OWNER.equals(value.get("owner").getAsString()))throw new IllegalStateException("control identity");
                String requested=value.get("phase").getAsString();
                if(!requested.matches("scan|ground|mine|place|reconnect"))throw new IllegalStateException("phase domain");
                if(!requested.equals(phase)){holding=false;phase=requested;done=false;actionStarted=false;reconnectStarted=false;stable=0;started=System.nanoTime();}
            }
            if(phase.isEmpty() || done)return;
            if(System.nanoTime()-started>90_000_000_000L)throw new IllegalStateException("phase timeout");
            if(phase.equals("reconnect") && actionStarted) {
                if(!reconnectStarted) {
                    // Vanilla's quit action closes the network channel; allow its removal before login.
                    if(System.nanoTime()-disconnectedAt<1_000_000_000L)return;
                    reconnectStarted=true;
                    ConnectScreen.startConnecting(new TitleScreen(),client,ServerAddress.parseString("127.0.0.1:25585"),
                        new ServerData("Owned gameplay proof","127.0.0.1:25585",ServerData.Type.OTHER),false,null);return;
                }
                if(client.player==null || client.level==null || client.gameMode==null)return;
            }
            checkContext(client);
            if(phase.equals("scan")){scan(client);complete(client);return;}
            if(stand==null || target==null)throw new IllegalStateException("scan required");
            if(client.player.getHealth()!=20 || !client.player.isAlive() || client.player.getY()<-64)throw new IllegalStateException("player damaged/falling");
            if(client.gameMode.getPlayerMode()!=GameType.SURVIVAL || client.player.getAbilities().flying || client.player.getAbilities().instabuild){stable=0;return;}
            if(!client.player.onGround()){stable=0;return;}
            if(Math.abs(client.player.getX()-(stand.getX()+0.5))>0.05 || Math.abs(client.player.getY()-stand.getY())>0.05 || Math.abs(client.player.getZ()-(stand.getZ()+0.5))>0.05)throw new IllegalStateException("natural landing position differs");
            activeMode=client.gameMode;
            switch(phase) {
                case "ground" -> {if(++stable>=20)complete(client);}
                case "mine" -> {
                    if(!client.level.getBlockState(target).isAir()) {
                        stable=0;select(client.player.getInventory(),Items.IRON_PICKAXE);holding=true;actionStarted=true;
                        client.gameMode.continueDestroyBlock(target,Direction.UP);miningCalls++;
                    }else if(++stable>=20){if(miningCalls<2)throw new IllegalStateException("original multi-tick mining not exercised");holding=false;client.gameMode.stopDestroyBlock();complete(client);}
                }
                case "place" -> {
                    select(client.player.getInventory(),Items.COBBLESTONE);
                    if(!actionStarted) {
                        if(!client.level.getBlockState(target).isAir())throw new IllegalStateException("mine result missing");
                        client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,
                            new BlockHitResult(new Vec3(target.getX()+0.5,target.getY(),target.getZ()+0.5),Direction.UP,target.below(),false));actionStarted=true;
                    }
                    if(client.level.getBlockState(target).is(Blocks.COBBLESTONE)){if(++stable>=20)complete(client);}else stable=0;
                }
                case "reconnect" -> {
                    if(!actionStarted) {
                        priorConnection=client.getConnection();actionStarted=true;
                        disconnectedAt=System.nanoTime();
                        client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);return;
                    }
                    if(client.getConnection()==priorConnection)return;
                    if(client.level.getBlockState(target).is(Blocks.COBBLESTONE)){if(++stable>=20)complete(client);}else stable=0;
                }
                default -> throw new IllegalStateException("phase domain");
            }
        }catch(Exception failure){holding=false;done=true;try{write(client,false,failure.toString());}catch(Exception ignored){}}
    }
    /** Hold only this owned virtual attack when an unfocused window releases input. */
    public static boolean holdsMining(MultiPlayerGameMode mode) {
        return holding && !done && phase.equals("mine") && mode==activeMode && System.nanoTime()-started<90_000_000_000L;
    }
    private static void checkContext(Minecraft client) {
        if(client.player==null || client.level==null || client.gameMode==null || client.getConnection()==null)throw new IllegalStateException("active original client required");
        if(!OWNER.equals(client.player.getGameProfile().name()) || !client.level.dimension().identifier().toString().equals("minecraft:overworld"))throw new IllegalStateException("actor/dimension differs");
        var address=client.getConnection().getConnection().getRemoteAddress();
        if(!(address instanceof InetSocketAddress socket) || !socket.getAddress().isLoopbackAddress() || socket.getPort()!=25585)throw new IllegalStateException("owned loopback connection required");
    }
    private static void scan(Minecraft client) {
        int cx=(int)Math.floor(client.player.getX()),cz=(int)Math.floor(client.player.getZ());
        for(int r=0;r<=96;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
            if(Math.abs(dx)!=r && Math.abs(dz)!=r)continue;
            int x=cx+dx,z=cz+dz;if(!client.level.hasChunk(x>>4,z>>4) || !client.level.hasChunk((x+1)>>4,z>>4))continue;
            visitedColumns++;int y=client.level.getHeight(Heightmap.Types.MOTION_BLOCKING,x,z);if(y<-60 || y>315)continue;
            BlockPos feet=new BlockPos(x,y,z),floor=feet.below(),hole=floor.east(),support=hole.below();
            var a=client.level.getBlockState(floor);var b=client.level.getBlockState(hole);var c=client.level.getBlockState(support);
            if(!naturalFloor(a) || !naturalFloor(b))continue;groundColumns++;
            if(!a.isCollisionShapeFullBlock(client.level,floor) || !b.isCollisionShapeFullBlock(client.level,hole) || !c.isCollisionShapeFullBlock(client.level,support) || !a.getFluidState().isEmpty() || !b.getFluidState().isEmpty() || !c.getFluidState().isEmpty())continue;
            if(!client.level.getBlockState(feet).isAir() || !client.level.getBlockState(feet.above()).isAir() || !client.level.getBlockState(feet.above(2)).isAir() || !client.level.getBlockState(hole.above()).isAir())continue;
            stand=feet;target=hole;standBlock=BuiltInRegistries.BLOCK.getKey(a.getBlock()).toString();
            targetBlock=BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString();supportBlock=BuiltInRegistries.BLOCK.getKey(c.getBlock()).toString();return;
        }
        throw new IllegalStateException("no bounded natural dry landing/mining patch");
    }
    private static boolean naturalFloor(BlockState state) {
        return switch(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()) {
            case "minecraft:grass_block","minecraft:dirt","minecraft:coarse_dirt","minecraft:rooted_dirt",
                "minecraft:stone","minecraft:andesite","minecraft:diorite","minecraft:granite","minecraft:deepslate",
                "minecraft:sandstone","minecraft:red_sandstone","minecraft:sand","minecraft:red_sand","minecraft:gravel" -> true;
            default -> false;
        };
    }
    private static void select(Inventory inventory,Item item) {
        for(int slot=0;slot<9;slot++)if(inventory.getItem(slot).is(item)){inventory.setSelectedSlot(slot);return;}
        throw new IllegalStateException("original given item not received");
    }
    private static void complete(Minecraft client)throws Exception{holding=false;done=true;write(client,true,null);}
    private static void write(Minecraft client,boolean success,String error)throws Exception {
        if(root==null || phase.isEmpty())return;
        var row=new LinkedHashMap<String,Object>();row.put("nonce",NONCE);row.put("owner",OWNER);row.put("phase",phase);row.put("success",success);row.put("error",error);
        row.put("client_screen",client.gui.screen()==null?null:client.gui.screen().getClass().getName());
        if(client.player!=null) {
            row.put("position",new double[]{client.player.getX(),client.player.getY(),client.player.getZ()});row.put("health",client.player.getHealth());row.put("alive",client.player.isAlive());
            row.put("on_ground",client.player.onGround());row.put("flying",client.player.getAbilities().flying);row.put("game_type",client.gameMode==null?-1:client.gameMode.getPlayerMode().getId());
        }
        if(stand!=null){row.put("stand",new int[]{stand.getX(),stand.getY(),stand.getZ()});row.put("target",new int[]{target.getX(),target.getY(),target.getZ()});row.put("stand_block",standBlock);row.put("target_block",targetBlock);row.put("support_block",supportBlock);}
        row.put("connection_changed",phase.equals("reconnect") && client.getConnection()!=priorConnection);
        row.put("original_mining_calls",miningCalls);
        row.put("visited_columns",visitedColumns);row.put("natural_floor_columns",groundColumns);
        if(target!=null && client.level!=null)row.put("actual_target_block",BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(target).getBlock()).toString());
        Path temp=root.resolve("result-"+phase+".new.json"),output=root.resolve("result-"+phase+".json");
        Files.writeString(temp,new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(row));Files.move(temp,output,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
