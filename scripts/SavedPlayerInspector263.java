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
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Offline original NBT reader, never a game, repair, teleport or health setter. */
public final class SavedPlayerInspector263 {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("one owned descriptor required");
        Path base = Path.of("test-artifacts").toRealPath();
        var descriptor = JsonParser.parseString(Files.readString(owned(base, Path.of(args[0])))).getAsJsonObject();
        Path output = Path.of(descriptor.get("output").getAsString()).toAbsolutePath().normalize();
        if (!output.startsWith(base) || output.equals(base) || Files.exists(output)) throw new IllegalArgumentException("new owned output required");
        var players = descriptor.getAsJsonArray("players");
        if (players.size() != 4) throw new IllegalArgumentException("exact two owners in two stopped conditions required");
        var rows = new ArrayList<LinkedHashMap<String,Object>>();
        boolean success = true;
        for (var entry : players) {
            var item = entry.getAsJsonObject();
            Path data = owned(base, Path.of(item.get("data").getAsString()));
            Path stats = owned(base, Path.of(item.get("stats").getAsString()));
            if (Files.size(data) > 1048576 || Files.size(stats) > 1048576) throw new IllegalArgumentException("saved player bound");
            String dataHash = hash(data), statsHash = hash(stats);
            var tag = NbtIo.readCompressed(data, NbtAccounter.create(16777216));
            var pos = tag.getListOrEmpty("Pos");
            if (pos.size() != 3) throw new IllegalArgumentException("saved position missing");
            double x = pos.getDouble(0).orElseThrow(), y = pos.getDouble(1).orElseThrow(), z = pos.getDouble(2).orElseThrow();
            float health = tag.getFloat("Health").orElseThrow();
            short death = tag.getShort("DeathTime").orElseThrow();
            int mode = tag.getInt("playerGameType").orElseThrow();
            var custom = JsonParser.parseString(Files.readString(stats)).getAsJsonObject()
                .getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
            long deaths = counter(custom, "minecraft:deaths"), damage = counter(custom, "minecraft:damage_taken");
            boolean safe = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && x == item.get("x").getAsDouble() && z == item.get("z").getAsDouble()
                && y >= -64 && y < 320 && health == 20 && death == 0 && mode == 1 && deaths == 0 && damage == 0;
            if (!dataHash.equals(hash(data)) || !statsHash.equals(hash(stats))) throw new IllegalStateException("saved player input changed");
            var row = new LinkedHashMap<String,Object>();
            row.put("condition", item.get("condition").getAsString()); row.put("owner", item.get("owner").getAsString());
            row.put("uuid", item.get("uuid").getAsString()); row.put("position", new double[]{x,y,z});
            row.put("health", health); row.put("death_time", death); row.put("game_type", mode);
            row.put("on_ground", tag.getBoolean("OnGround").orElse(null));
            row.put("flying", tag.getCompoundOrEmpty("abilities").getBoolean("flying").orElse(null));
            row.put("recorded_deaths", deaths); row.put("recorded_damage_taken", damage);
            row.put("data_sha256", dataHash); row.put("stats_sha256", statsHash); row.put("success", safe);
            rows.add(row); success &= safe;
        }
        var report = new LinkedHashMap<String,Object>();
        report.put("schema", "worldgen-assist.saved-player-safety.v1"); report.put("success", success); report.put("players", rows);
        report.put("scope", "Exact stopped ordinary creative worlds, original NBT/statistics, final position/health and recorded deaths/damage only; not continuous traces or survival interactions.");
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report));
        if (!success) System.exit(1);
    }
    private static Path owned(Path base, Path path) throws Exception {
        Path resolved = path.toRealPath();
        if (!resolved.startsWith(base) || resolved.equals(base)) throw new IllegalArgumentException("owned saved input required");
        return resolved;
    }
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static long counter(JsonObject custom, String name) {
        return custom != null && custom.has(name) ? custom.get(name).getAsLong() : 0;
    }
}
