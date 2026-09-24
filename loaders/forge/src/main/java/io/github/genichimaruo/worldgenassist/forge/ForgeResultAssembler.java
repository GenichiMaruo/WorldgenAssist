package io.github.genichimaruo.worldgenassist.forge;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.network.ForgeResultFragmentPayload;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread-only, bounded reassembly of Forge result fragments. */
final class ForgeResultAssembler {
    private static final int MAX_ACTIVE = 128;
    private static final int MAX_PER_OWNER = 64;
    private static final long EXPIRY_NANOS = 60_000_000_000L;
    private static final Map<Key, Assembly> ACTIVE = new HashMap<>();

    private ForgeResultAssembler() {}

    static TerrainDensityResultEnvelope accept(UUID owner, ForgeResultFragmentPayload fragment) {
        prune();
        Key key = new Key(owner, fragment.identity().jobId());
        Assembly assembly = ACTIVE.get(key);
        if (assembly == null) {
            if (ACTIVE.size() >= MAX_ACTIVE || ACTIVE.keySet().stream().filter(k -> k.owner().equals(owner)).count() >= MAX_PER_OWNER) {
                WorldgenAssist.LOGGER.warn("[CAWG] forge.fragment_rejected id={} owner={} reason=capacity", key.jobId(), owner);
                return null;
            }
            assembly = new Assembly(fragment);
            ACTIVE.put(key, assembly);
        }
        if (!assembly.matches(fragment) || !assembly.add(fragment)) {
            ACTIVE.remove(key);
            WorldgenAssist.LOGGER.warn("[CAWG] forge.fragment_rejected id={} owner={} reason=inconsistent", key.jobId(), owner);
            return null;
        }
        if (!assembly.complete()) return null;
        ACTIVE.remove(key);
        return assembly.result();
    }

    static void removeOwner(UUID owner) { ACTIVE.keySet().removeIf(key -> key.owner().equals(owner)); }
    static void clear() { ACTIVE.clear(); }
    static void prune() {
        long now = System.nanoTime();
        ACTIVE.values().removeIf(assembly -> now - assembly.startedNanos > EXPIRY_NANOS);
    }

    private record Key(UUID owner, UUID jobId) {}

    private static final class Assembly {
        private final ForgeResultFragmentPayload first;
        private final byte[][] parts;
        private final long startedNanos = System.nanoTime();
        private int received;

        private Assembly(ForgeResultFragmentPayload first) {
            this.first = first;
            this.parts = new byte[first.partCount()][];
        }

        private boolean matches(ForgeResultFragmentPayload part) {
            return first.identity().equals(part.identity())
                && first.densityCount() == part.densityCount()
                && first.encoding() == part.encoding()
                && first.totalBytes() == part.totalBytes()
                && first.clientComputeNanos() == part.clientComputeNanos()
                && first.clientEncodeNanos() == part.clientEncodeNanos()
                && first.partCount() == part.partCount();
        }

        private boolean add(ForgeResultFragmentPayload part) {
            byte[] bytes = part.bytes();
            byte[] previous = parts[part.partIndex()];
            if (previous != null) return Arrays.equals(previous, bytes);
            parts[part.partIndex()] = bytes;
            received++;
            return true;
        }

        private boolean complete() { return received == parts.length; }

        private TerrainDensityResultEnvelope result() {
            byte[] joined = new byte[first.totalBytes()];
            for (int index = 0; index < parts.length; index++)
                System.arraycopy(parts[index], 0, joined, index * ForgeResultFragmentPayload.MAX_PART_BYTES, parts[index].length);
            return new TerrainDensityResultEnvelope(first.identity(), first.densityCount(), first.encoding(), joined,
                first.clientComputeNanos(), first.clientEncodeNanos());
        }
    }
}
