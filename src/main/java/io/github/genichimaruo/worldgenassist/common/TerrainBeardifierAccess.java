package io.github.genichimaruo.worldgenassist.common;

import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/** Read only snapshot of the actual server-produced sampler, including native traversal hooks. */
public interface TerrainBeardifierAccess {
	List<Beardifier.Rigid> worldgenAssist$rigids();
	List<JigsawJunction> worldgenAssist$junctions();
	BoundingBox worldgenAssist$affectedBox();
}
