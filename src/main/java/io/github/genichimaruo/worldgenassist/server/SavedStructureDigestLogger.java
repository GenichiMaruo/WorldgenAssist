package io.github.genichimaruo.worldgenassist.server;

import java.nio.file.Files;
import java.util.regex.Pattern;
import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;

/** Optional fixture-only evidence after the server has saved and closed its worlds. */
public final class SavedStructureDigestLogger {
	private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private SavedStructureDigestLogger() {}
	public static void log(MinecraftServer server) {
		if (!DecorationStageDigestLogger.enabled()) return;
		var directory = DimensionType.getStorageFolder(Level.OVERWORLD, server.getWorldPath(LevelResource.ROOT)).resolve("region");
		int chunks = 0;
		try (var files = Files.list(directory)) {
			for (var path : files.sorted().toList()) {
				var match = REGION.matcher(path.getFileName().toString());
				if (!match.matches()) continue;
				int baseX = Math.multiplyExact(Integer.parseInt(match.group(1)), 32);
				int baseZ = Math.multiplyExact(Integer.parseInt(match.group(2)), 32);
				try (var region = new RegionFile(new RegionStorageInfo("worldgen_assist_fixture", Level.OVERWORLD, "chunk"), path, directory, false)) {
					for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
						var pos = new ChunkPos(Math.addExact(baseX, x), Math.addExact(baseZ, z));
						try (var input = region.getChunkDataInputStream(pos)) {
							if (input == null) continue;
							var tag = NbtIo.read(input, NbtAccounter.create(16L * 1024 * 1024));
							String status = tag.getString("Status").orElseThrow();
							if (!status.equals("minecraft:full") && !status.equals("full")) continue;
							if (tag.getInt("xPos").orElseThrow() != pos.x() || tag.getInt("zPos").orElseThrow() != pos.z())
								throw new IllegalStateException("saved chunk coordinate mismatch");
							var structures = tag.get("structures");
							if (structures == null) throw new IllegalStateException("saved structures missing");
							String digest = DecorationStageDigest.hash(out -> DecorationStageDigest.writeTag(out, structures, false));
							WorldgenAssist.LOGGER.info(
								"[CAWG] stage.digest stage=saved_structures chunk={},{} format=1 algorithm=SHA-256 digest={} dimension=minecraft:overworld",
								pos.x(), pos.z(), digest);
							String lightDigest = SavedLightDigest.compute(tag);
							WorldgenAssist.LOGGER.info(
								"[CAWG] stage.digest stage=saved_light chunk={},{} format=1 algorithm=SHA-256 digest={} dimension=minecraft:overworld",
								pos.x(), pos.z(), lightDigest);
							chunks++;
						}
					}
				}
			}
			WorldgenAssist.LOGGER.info("[CAWG] saved_structures.complete chunks={}", chunks);
			WorldgenAssist.LOGGER.info("[CAWG] saved_light.complete chunks={}", chunks);
		} catch (Exception error) {
			WorldgenAssist.LOGGER.error("[CAWG] saved_structures.failed chunks={}", chunks, error);
		}
	}
}
