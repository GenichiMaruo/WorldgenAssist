import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import jdk.jfr.consumer.*;

/** Offline only: read existing recordings without exporting multi-gigabyte JSON. */
public class AnalyzeWorldgenJfr {
    private record Group(String name, Predicate<String> matches) { }
    private static final List<Group> GROUPS = List.of(
        new Group("terrain fill", n -> n.equals("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.doFill")
            || n.equals("io.github.genichimaruo.worldgenassist.server.TerrainDecisionFiller.fill")),
        new Group("surface material", n -> n.equals("net.minecraft.world.level.levelgen.material.MaterialSystem.buildSurface")),
        new Group("material rule density", n -> n.contains("MaterialRuleContext.") && n.contains("getDensitiesInChunk")),
        new Group("ore vein rule", n -> n.startsWith("net.minecraft.world.level.levelgen.material.rule.OreVeinRule")),
        new Group("density samplers", n -> n.startsWith("net.minecraft.world.level.levelgen.densityfunction.")),
        new Group("biome selection", n -> n.startsWith("net.minecraft.world.level.biome.")),
        new Group("aquifers", n -> n.startsWith("net.minecraft.world.level.levelgen.Aquifer$")),
        new Group("features", n -> n.equals("net.minecraft.world.level.chunk.ChunkGenerator.applyBiomeDecoration")),
        new Group("carvers", n -> n.equals("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.generateCarvers")),
        new Group("remote validation", n -> n.startsWith("io.github.genichimaruo.worldgenassist.server.RemoteDensityValidator.")),
        new Group("remote management", n -> n.startsWith("io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager.")),
        new Group("candidate queue", n -> n.startsWith("io.github.genichimaruo.worldgenassist.server.GenerationPrefetchQueue.")),
        new Group("readiness queue", n -> n.startsWith("io.github.genichimaruo.worldgenassist.server.RemoteAwareTerrainQueue.")),
        new Group("eligibility", n -> n.startsWith("io.github.genichimaruo.worldgenassist.server.RemoteWorldgenEligibility.")),
        new Group("heightmaps", n -> n.startsWith("net.minecraft.world.level.levelgen.Heightmap.")),
        new Group("logging", n -> n.startsWith("org.apache.logging.log4j.")),
        new Group("compression", n -> n.startsWith("java.util.zip."))
    );

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("recording start_utc end_utc output artifact_sha256");
        Path recording = Path.of(args[0]).toRealPath(), output = Path.of(args[3]).toAbsolutePath().normalize();
        Path evidence = Path.of("test-artifacts").toRealPath();
        if (!recording.startsWith(evidence) || !output.startsWith(evidence) || output.equals(evidence)
            || output.equals(recording)) throw new IllegalArgumentException("Paths must be separate workspace evidence children");
        if (Files.exists(output)) throw new IllegalArgumentException("Analysis evidence already exists: " + output);
        Instant start = Instant.parse(args[1]), end = Instant.parse(args[2]);
        if (!start.isBefore(end) || !args[4].matches("[0-9A-Fa-f]{64}")) throw new IllegalArgumentException("Invalid identity/window");
        Map<String, Long> leaves = new HashMap<>(), inclusive = new HashMap<>(), categories = new HashMap<>(), threads = new HashMap<>();
        long samples = 0, truncated = 0;
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")
                    || event.getStartTime().isBefore(start) || event.getStartTime().isAfter(end)) continue;
                RecordedStackTrace stack = event.getStackTrace();
                if (stack == null || stack.getFrames().isEmpty()) continue;
                samples++; if (stack.isTruncated()) truncated++;
                List<String> names = stack.getFrames().stream().map(frame ->
                    frame.getMethod().getType().getName().replace('/', '.') + "." + frame.getMethod().getName()).toList();
                add(leaves, names.getFirst());
                for (String name : new HashSet<>(names)) add(inclusive, name);
                RecordedThread thread = event.getThread("sampledThread");
                add(threads, thread == null || thread.getJavaName() == null ? "unknown" : thread.getJavaName());
                for (Group group : GROUPS) if (names.stream().anyMatch(group.matches())) add(categories, group.name());
            }
        }
        if (samples < 100) throw new IllegalStateException("Insufficient computational samples: " + samples);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(recording)) {
            byte[] bytes = new byte[65536]; int read;
            while ((read = input.read(bytes)) >= 0) digest.update(bytes, 0, read);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "worldgen-assist.jfr-compute.v1");
        report.put("artifact_sha256", args[4].toUpperCase(Locale.ROOT));
        report.put("recording_sha256", HexFormat.of().withUpperCase().formatHex(digest.digest()));
        report.put("window_start_utc", start.toString()); report.put("window_end_utc", end.toString());
        report.put("computational_samples", samples); report.put("truncated_stacks", truncated);
        report.put("categories", table(categories, samples, Integer.MAX_VALUE));
        report.put("threads", table(threads, samples, Integer.MAX_VALUE));
        report.put("leaf_methods", table(leaves, samples, 60));
        report.put("inclusive_methods", table(inclusive, samples, 150));
        report.put("definition", "Existing measured-window jdk.ExecutionSample only; native waits excluded. Inclusive categories overlap; percentages are neither CPU durations nor end-to-end savings.");
        Files.writeString(output, json(report) + System.lineSeparator());
        System.out.println("JFR_ANALYSIS_COMPLETE samples=" + samples + " output=" + output);
    }

    private static void add(Map<String, Long> counts, String name) { counts.merge(name, 1L, Long::sum); }
    private static List<Map<String, Object>> table(Map<String, Long> counts, long total, int limit) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
            .thenComparing(Map.Entry.comparingByKey())).limit(limit).map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("name", entry.getKey());
                row.put("samples", entry.getValue()); row.put("percent", 100.0 * entry.getValue() / total); return row;
            }).toList();
    }
    private static String json(Object value) {
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream()
            .map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).toList()) + "}";
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(AnalyzeWorldgenJfr::json).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        StringBuilder quoted = new StringBuilder("\"");
        for (char ch : value.toString().toCharArray()) {
            if (ch == '\\' || ch == '"') quoted.append('\\').append(ch);
            else if (ch < 32) quoted.append(String.format(Locale.ROOT, "\\u%04x", (int)ch));
            else quoted.append(ch);
        }
        return quoted.append('"').toString();
    }
}
