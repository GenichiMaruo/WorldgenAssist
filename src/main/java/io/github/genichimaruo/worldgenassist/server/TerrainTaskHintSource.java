package io.github.genichimaruo.worldgenassist.server;

import java.util.function.Consumer;
import net.minecraft.server.level.GenerationChunkHolder;

/** Read existing task claims only; hints never acquire claims or authorize writes. */
public interface TerrainTaskHintSource {
	void worldgenAssist$visitTerrainCandidates(Consumer<GenerationChunkHolder> visitor);
}
