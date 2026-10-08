package io.github.genichimaruo.worldgenassist.probe;

import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/** Unshipped native equivalent of Fabric's measured chunk receipt observer. */
public final class NativeReceiptProbe {
    private static final Pattern MARKER=Pattern.compile("CAWG_SCENARIO_MEASURED_(BEGIN|END)_(\\d+)");
    private static int repeat;
    private static boolean enabled() {
        var nonce=System.getenv("WORLDGEN_GAMEPLAY_PROBE_NONCE");
        var owner=System.getenv("WORLDGEN_GAMEPLAY_PROBE_OWNER");
        var client=Minecraft.getInstance();
        if(nonce==null || !nonce.matches("[a-f0-9]{32}") || owner==null || !owner.matches("ScenarioOwner[AB]")
            || !"true".equalsIgnoreCase(System.getenv("WORLDGEN_ASSIST_CLIENT_MEASURE_RECEIPT"))
            || client.player==null || !owner.equals(client.player.getGameProfile().name()) || client.getConnection()==null)return false;
        var address=client.getConnection().getConnection().getRemoteAddress();
        return address instanceof InetSocketAddress socket && socket.getAddress().isLoopbackAddress() && socket.getPort()==25585;
    }
    public static void marker(String text) {
        if(!enabled())return;
        var match=MARKER.matcher(text);if(!match.find())return;
        int value=Integer.parseInt(match.group(2));if("BEGIN".equals(match.group(1)))repeat=value;
        LoggerFactory.getLogger("worldgen_gameplay_probe").info("[CAWG] benchmark.client_marker phase={} repeat={} nanos={}",match.group(1),value,System.nanoTime());
    }
    public static void dimensionChanged() {
        if(!"true".equalsIgnoreCase(System.getenv("WORLDGEN_ASSIST_NATIVE_LIFETIME_PROBE")) || !enabled())return;
        var client=Minecraft.getInstance();
        if(client.level!=null)LoggerFactory.getLogger("worldgen_gameplay_probe").info(
            "[CAWG] benchmark.native_dimension dimension={} nanos={}",client.level.dimension().identifier(),System.nanoTime());
    }
    public static void chunk(int x,int z) {
        if(repeat>0 && enabled())LoggerFactory.getLogger("worldgen_gameplay_probe").info("[CAWG] benchmark.chunk_received repeat={} chunk={},{} nanos={}",repeat,x,z,System.nanoTime());
    }
}
