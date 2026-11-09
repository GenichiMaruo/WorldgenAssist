package io.github.genichimaruo.worldgenassist.probe;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.LinkedHashMap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameType;

/** Unshipped, nonce-bound loopback driver. Original physics, movement and packet routing run. */
public final class BetaStabilityProbe {
    private static final String NONCE=System.getenv("WORLDGEN_GAMEPLAY_PROBE_NONCE"), OWNER=System.getenv("WORLDGEN_GAMEPLAY_PROBE_OWNER");
    private static Path root;
    private static String phase="";
    private static int ticks, stable, reversals, pauseDismissals;
    private static boolean done, moving, ascending, connecting, armed, forward=true;
    private static long started, lastReport, lastPauseDismissal;
    private static double centerX,centerZ,lastX,lastZ,travel;
    private static int[] stand,target;
    public static void holdInput(Minecraft c) {
        if((phase.equals("arm") || phase.equals("explore")) && !done){c.options.keyUp.setDown(moving);c.options.keyJump.setDown(ascending);}
    }
    public static void tick(Minecraft c) {
        if(NONCE==null || !NONCE.matches("[a-f0-9]{32}") || OWNER==null || !OWNER.matches("ScenarioOwner[AB]"))return;
        try {
            if(root==null) {
                String configured=System.getenv("WORLDGEN_GAMEPLAY_PROBE_ROOT");if(configured==null)return;
                root=Path.of(configured).toRealPath();
                if(!root.startsWith(c.gameDirectory.toPath().toRealPath()) || !root.getFileName().toString().equals("gameplay-probe"))throw new IllegalStateException("owned root");
            }
            if(++ticks%5!=0)return;
            Path file=root.resolve("beta-control.json");if(!Files.exists(file))return;
            if(Files.size(file)>4096)throw new IllegalStateException("control bound");
            var control=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(!NONCE.equals(control.get("nonce").getAsString()) || !OWNER.equals(control.get("owner").getAsString()))throw new IllegalStateException("control identity");
            String requested=control.get("phase").getAsString();
            if(!requested.matches("arm|explore|stop|rejoin"))throw new IllegalStateException("phase domain");
            if(!requested.equals(phase)) {
                moving=false;ascending=false;c.options.keyUp.setDown(false);c.options.keyJump.setDown(false);phase=requested;done=false;stable=0;connecting=false;started=System.nanoTime();
                var saved=JsonParser.parseString(Files.readString(root.resolve("result-scan.json"))).getAsJsonObject();
                stand=new int[3];target=new int[3];for(int i=0;i<3;i++){stand[i]=saved.getAsJsonArray("stand").get(i).getAsInt();target[i]=saved.getAsJsonArray("target").get(i).getAsInt();}
                centerX=stand[0]+0.5;centerZ=stand[2]+0.5;
                if(phase.equals("arm"))armed=false;
                if(phase.equals("explore")){
                    if(!armed)throw new IllegalStateException("missing airborne handshake");
                    armed=false;travel=0;reversals=0;forward=true;lastX=centerX;lastZ=centerZ;
                }
            }
            if(done && !(armed && phase.equals("arm")))return;
            if(System.nanoTime()-started>(phase.equals("explore")?4_200_000_000_000L:120_000_000_000L))throw new IllegalStateException("bounded phase timeout");
            if(phase.equals("rejoin") && !connecting) {
                connecting=true;
                ConnectScreen.startConnecting(new TitleScreen(),c,ServerAddress.parseString("127.0.0.1:25585"),new ServerData("Owned beta restart","127.0.0.1:25585",ServerData.Type.OTHER),false,null);return;
            }
            if(c.player==null || c.level==null || c.gameMode==null || c.getConnection()==null){if(phase.equals("rejoin"))return;throw new IllegalStateException("unexpected disconnect");}
            var address=c.getConnection().getConnection().getRemoteAddress();
            if(!(address instanceof InetSocketAddress s) || !s.getAddress().isLoopbackAddress() || s.getPort()!=25585 || !OWNER.equals(c.player.getGameProfile().name()) || !c.level.dimension().identifier().toString().equals("minecraft:overworld"))throw new IllegalStateException("actor/connection/dimension");
            if(c.player.getHealth()!=20 || !c.player.isAlive() || c.player.getY()<-64)throw new IllegalStateException("damage/death/fall");
            if((phase.equals("arm") || phase.equals("explore")) && c.gameMode.getPlayerMode()==GameType.CREATIVE && c.player.getAbilities().mayfly){
                var screen=c.gui.screen();
                if(screen!=null){
                    if(screen.getClass()!=PauseScreen.class)throw new IllegalStateException("unexpected input screen: "+screen.getClass().getName());
                    long now=System.nanoTime();
                    if(pauseDismissals>=2 || (pauseDismissals>0 && now-lastPauseDismissal<60_000_000_000L))throw new IllegalStateException("persistent or repeated pause menu");
                    pauseDismissals++;lastPauseDismissal=now;
                    screen.onClose();
                    if(c.gui.screen()!=null)throw new IllegalStateException("pause menu did not return to game");
                }
            }
            if(done){
                // One owner can be ready earlier; sample its actual state until both are ready.
                if(System.nanoTime()-lastReport>2_000_000_000L){write(c,true,"");lastReport=System.nanoTime();}
                return;
            }
            if(phase.equals("arm")) {
                // Start over the saved natural patch. Require a real lift and a level hover
                // before the server positions this client at the start of the corridor.
                if(System.nanoTime()-started>45_000_000_000L)throw new IllegalStateException("creative takeoff did not settle");
                if(c.gameMode.getPlayerMode()!=GameType.CREATIVE || !c.player.getAbilities().mayfly || c.gui.screen()!=null)return;
                double lift=c.player.getY()-stand[1];
                if(Math.abs(c.player.getX()-centerX)>0.1 || Math.abs(c.player.getZ()-centerZ)>0.1 || lift< -0.2 || lift>12)throw new IllegalStateException("takeoff left the saved patch");
                ascending=lift<3;
                holdInput(c);
                // Let the original jump lift the player first: aiStep clears flight on ground.
                if(!c.player.onGround() && !c.player.getAbilities().flying){c.player.getAbilities().flying=true;c.player.onUpdateAbilities();}
                if(lift>=2 && !c.player.onGround() && c.player.getAbilities().flying && !ascending && Math.abs(c.player.getDeltaMovement().y)<0.05 &&
                   c.level.hasChunk(stand[0]>>4,stand[2]>>4))stable++;else stable=0;
                if(stable>=4){armed=true;done=true;write(c,true,"");}
            } else if(phase.equals("explore")) {
                if(c.gameMode.getPlayerMode()!=GameType.CREATIVE || !c.player.getAbilities().mayfly){return;}
                if(c.gui.screen()!=null || !c.player.getAbilities().flying)throw new IllegalStateException("lost creative flight or input context");
                if(c.player.getY()<327)ascending=true;
                if(c.player.getY()>330)ascending=false;
                holdInput(c);
                if(c.player.getY()<325 || c.player.getY()>338){if(System.nanoTime()-started<45_000_000_000L)return;throw new IllegalStateException("flight height escaped");}
                if(Math.abs(c.player.getX()-centerX)>8 || Math.abs(c.player.getZ()-centerZ)>800)throw new IllegalStateException("flight corridor escaped");
                double dz=c.player.getZ()-centerZ;
                if(forward && dz>768 || !forward && dz< -768){forward=!forward;reversals++;}
                c.player.setYRot(forward?0:180);c.player.setXRot(0);moving=true;holdInput(c);
                double step=Math.hypot(c.player.getX()-lastX,c.player.getZ()-lastZ);if(step>15)throw new IllegalStateException("movement discontinuity");
                travel+=step;lastX=c.player.getX();lastZ=c.player.getZ();
                if(System.nanoTime()-lastReport>5_000_000_000L){write(c,true,"");lastReport=System.nanoTime();}
            } else {
                moving=false;ascending=false;c.options.keyUp.setDown(false);c.options.keyJump.setDown(false);
                if(phase.equals("stop")){done=true;write(c,true,"");return;}
                if(c.gameMode.getPlayerMode()!=GameType.SURVIVAL || c.player.getAbilities().flying || !c.player.onGround() || Math.abs(c.player.getX()-centerX)>0.05 || Math.abs(c.player.getY()-stand[1])>0.05 || Math.abs(c.player.getZ()-centerZ)>0.05){stable=0;return;}
                if(!c.level.hasChunk(target[0]>>4,target[2]>>4) || !BuiltInRegistries.BLOCK.getKey(c.level.getBlockState(new BlockPos(target[0],target[1],target[2])).getBlock()).toString().equals("minecraft:cobblestone")){stable=0;return;}
                if(++stable>=20){done=true;write(c,true,"");}
            }
        }catch(Exception e){moving=false;ascending=false;armed=false;c.options.keyUp.setDown(false);c.options.keyJump.setDown(false);done=true;try{write(c,false,e.toString());}catch(Exception reportError){
            System.err.println("Beta probe failure for "+OWNER+" ("+phase+"): "+e+"; failure report could not be published: "+reportError);
        }}
    }
    private static void write(Minecraft c,boolean success,String error)throws Exception {
        var row=new LinkedHashMap<String,Object>();row.put("nonce",NONCE);row.put("owner",OWNER);row.put("phase",phase);row.put("success",success);row.put("error",error);row.put("elapsed_ms",(System.nanoTime()-started)/1_000_000);row.put("travel_blocks",travel);row.put("reversals",reversals);row.put("client_epoch_ms",System.currentTimeMillis());
        if(c.player!=null){row.put("health",c.player.getHealth());row.put("position",new double[]{c.player.getX(),c.player.getY(),c.player.getZ()});row.put("on_ground",c.player.onGround());row.put("flying",c.player.getAbilities().flying);row.put("vertical_velocity",c.player.getDeltaMovement().y);}
        var screen=c.gui.screen();row.put("screen",screen==null?"":screen.getClass().getName());row.put("arm_ready",armed);row.put("pause_dismissals",pauseDismissals);
        row.put("loaded_current_chunk",c.player!=null && c.level!=null && c.level.hasChunk(((int)Math.floor(c.player.getX()))>>4,((int)Math.floor(c.player.getZ()))>>4));
        Path tmp=root.resolve("beta-state.new.json"),target=root.resolve("beta-state.json");
        Files.writeString(tmp,new GsonBuilder().create().toJson(row));
        // A Windows reader can briefly deny replacement. Retry only the move of these
        // already captured bytes; the observation timestamp and failure/success never change.
        for(int attempt=0;;attempt++){
            try{Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);return;}
            catch(AccessDeniedException denied){
                if(attempt==3)throw new java.io.IOException("Atomic beta report replacement denied after four attempts: "+denied.getMessage(),denied);
                try{Thread.sleep(2L << attempt);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
            }
        }
    }
}
