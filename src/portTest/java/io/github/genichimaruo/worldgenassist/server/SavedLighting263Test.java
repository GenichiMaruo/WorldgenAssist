package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

class SavedLighting263Test {
	@Test void savedValuesKeepMissingSkySemanticsAndCorruptNibblesWithoutRepair() {
		var sky = new DataLayer(15); sky.set(3,0,5,0);
		var block = new DataLayer(); block.set(3,0,5,15);
		var tag = new CompoundTag(); tag.putInt("xPos",0); tag.putInt("zPos",0); tag.putBoolean("isLightOn",true);
		var sections = new ListTag(); var section = new CompoundTag(); section.putByte("Y",(byte)0);
		section.putByteArray("SkyLight",sky.getData().clone()); section.putByteArray("BlockLight",block.getData().clone());
		sections.add(section); tag.put("sections",sections);
		var view = new SavedLightView263(null,new ChunkPos(0,0),tag,java.util.List.of(0));
		assertEquals(0,view.sky(new BlockPos(3,-16,5))); // Original missing sky layer repeats the bottom row above it.
		assertEquals(15,view.sky(new BlockPos(4,-16,5)));
		assertEquals(15,view.sky(new BlockPos(3,16,5))); // Above saved top uses original open-sky value.
		assertEquals(15,view.block(new BlockPos(3,0,5))); assertEquals(0,view.block(new BlockPos(3,-16,5)));
		sky.set(3,0,5,7); block.set(3,0,5,6);
		section.putByteArray("SkyLight",sky.getData().clone()); section.putByteArray("BlockLight",block.getData().clone());
		var corrupt = new SavedLightView263(null,new ChunkPos(0,0),tag,java.util.List.of(0));
		assertEquals(7,corrupt.sky(new BlockPos(3,0,5))); assertEquals(6,corrupt.block(new BlockPos(3,0,5)));
		assertEquals(0,view.sky(new BlockPos(3,0,5))); assertEquals(15,view.block(new BlockPos(3,0,5)));
		section.putByteArray("BlockLight",new byte[2047]);
		assertThrows(IllegalArgumentException.class,() -> new SavedLightView263(null,new ChunkPos(0,0),tag,java.util.List.of(0)));
		section.putByteArray("BlockLight",new byte[2048]); section.remove("SkyLight");
		var omittedZero = new SavedLightView263(null,new ChunkPos(0,0),tag,java.util.List.of(0));
		assertEquals(0,omittedZero.sky(new BlockPos(3,0,5)));
		assertEquals(0,omittedZero.sky(new BlockPos(3,-16,5)));
		assertEquals(15,omittedZero.sky(new BlockPos(3,16,5)));
	}
}
