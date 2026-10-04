package io.github.genichimaruo.worldgenassist.mixin;

import java.util.List;
import io.github.genichimaruo.worldgenassist.common.TerrainBeardifierAccess;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Generated26.3 Fabric/Forge/NeoForge fields share these JVM descriptors. */
@Mixin(Beardifier.class)
public interface TerrainBeardifierAccessor263 extends TerrainBeardifierAccess {
	@Override @Accessor("pieces") List<Beardifier.Rigid> worldgenAssist$rigids();
	@Override @Accessor("junctions") List<JigsawJunction> worldgenAssist$junctions();
	@Override @Accessor("affectedBox") BoundingBox worldgenAssist$affectedBox();
}
