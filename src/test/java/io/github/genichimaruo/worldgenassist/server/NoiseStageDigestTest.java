package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NoiseStageDigestTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void canonicalBlockStateUsesRegistryNameAndSortedProperties() {
		assertEquals("minecraft:stone", NoiseStageDigest.canonicalBlockState(Blocks.STONE.defaultBlockState()));
		assertEquals(
			"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
			NoiseStageDigest.canonicalBlockState(Blocks.OAK_STAIRS.defaultBlockState())
		);
	}
}
