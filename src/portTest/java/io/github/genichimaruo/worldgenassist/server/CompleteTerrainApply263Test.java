package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import io.github.genichimaruo.worldgenassist.common.CompleteTerrainData;
import io.github.genichimaruo.worldgenassist.common.CompleteTerrainPalette;
import net.minecraft.core.IdMapper;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.Test;

class CompleteTerrainApply263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void boundedColumnsPreserveEveryVoxelAndSectionCountAndRejectWrongHeights() throws Exception {
		int height = 384;
		var palette = new CompleteTerrainPalette();
		BlockState[] states = new BlockState[palette.size()];
		boolean[] surfacePredicate = new boolean[states.length], floorPredicate = new boolean[states.length];
		for (int code = 0; code < states.length; code++) {
			states[code] = palette.state(code);
			surfacePredicate[code] = !states[code].isAir();
			// Explicit fixture predicates: bootstrap does not bind runtime block tags.
			floorPredicate[code] = !states[code].isAir() && states[code].getFluidState().isEmpty();
		}
		byte[] choices = new byte[height * 256];
		var random = new java.util.Random(8675309);
		for (int column = 0; column < 256; column++) for (int y = 0; y < height; y++) {
			int code = switch (column % 8) {
				case 0 -> 0;
				case 1 -> y == 383 ? 1 : 0; // Cave air alone, above both zero WG heights.
				case 2 -> y == 95 ? 1 : y < 45 ? 2 : y < 70 ? 5 : 0;
				case 3 -> 2;
				case 4 -> y == 0 ? 4 : 0;
				case 5 -> y < 96 ? random.nextInt(states.length) : y == 200 ? 1 : 0;
				case 6 -> y < 50 ? (y % 2 == 0 ? 2 : 1) : 0;
				default -> y < 128 ? 5 : 0; // No floor match: must search the whole column.
			};
			choices[column * height + y] = (byte)code;
		}
		short[] surface = new short[256], floor = new short[256], highest = new short[256];
		java.util.Arrays.fill(highest, (short)-1);
		// Independent exhaustive oracle: ascending complete volume, no early exit.
		for (int column = 0; column < 256; column++) for (int y = 0; y < height; y++) {
			int code = Byte.toUnsignedInt(choices[column * height + y]);
			if (surfacePredicate[code]) surface[column] = (short)(y + 1);
			if (floorPredicate[code]) floor[column] = (short)(y + 1);
			if (code != 0) highest[column] = (short)y;
		}
		short[][] offsets = new short[height / 16][];
		java.util.Arrays.setAll(offsets, i -> new short[0]);
		var data = new CompleteTerrainData(-64, height, choices, surface, floor, offsets, new byte[32]);
		short[] plan = CompleteTerrainApplicator.prepareColumns(data, surfacePredicate, floorPredicate);
		assertArrayEquals(highest, plan);
		var plains = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		IdMapper<Holder<Biome>> biomeIds = new IdMapper<>(); biomeIds.add(plains);
		LevelChunkSection[] actual = new LevelChunkSection[height / 16], expected = new LevelChunkSection[height / 16];
		for (int i = 0; i < actual.length; i++) {
			actual[i] = new LevelChunkSection(new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)),
				new PalettedContainer<>(plains, Strategy.createForBiomes(biomeIds)));
			expected[i] = actual[i].copy(); actual[i].acquire();
		}
		try { CompleteTerrainApplicator.writeBlocks(data, states, plan, actual); }
		finally { for (int i = actual.length - 1; i >= 0; i--) actual[i].release(); }
		LevelChunkSection[] bulk = new LevelChunkSection[actual.length];
		for (int i = 0; i < bulk.length; i++) bulk[i] = expected[i].copy();
		var stateObjects = java.util.Arrays.stream(bulk).map(LevelChunkSection::getStates).toArray();
		var biomeObjects = java.util.Arrays.stream(bulk).map(LevelChunkSection::getBiomes).toArray();
		byte[][] packed = CompleteTerrainApplicator.packBlocks(data, states);
		CompleteTerrainApplicator.readBlocks(packed, bulk);
		assertThrows(IllegalArgumentException.class, () -> CompleteTerrainApplicator.readBlocks(new byte[0][], bulk));
		for (int i = 0; i < bulk.length; i++) {
			assertSame(stateObjects[i], bulk[i].getStates()); assertSame(biomeObjects[i], bulk[i].getBiomes());
			assertSame(plains, bulk[i].getNoiseBiome(3, 3, 3));
		}
		for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = height - 1; y >= 0; y--) {
			BlockState state = states[data.choice((z * 16 + x) * height + y)];
			if (state != Blocks.AIR.defaultBlockState()) expected[y / 16].setBlockState(x, y & 15, z, state);
			assertSame(state, actual[y / 16].getBlockState(x, y & 15, z));
			assertSame(state, bulk[y / 16].getBlockState(x, y & 15, z));
		}
		for (String name : new String[]{"nonEmptyBlockCount", "fluidCount", "tickingBlockCount", "tickingFluidCount"}) {
			var field = LevelChunkSection.class.getDeclaredField(name); field.setAccessible(true);
			for (int i = 0; i < actual.length; i++) {
				assertEquals(field.getShort(expected[i]), field.getShort(actual[i]), name + "/" + i);
				assertEquals(field.getShort(expected[i]), field.getShort(bulk[i]), "bulk/" + name + "/" + i);
			}
		}
		for (boolean corruptSurface : new boolean[]{true, false}) {
			short[] badSurface = surface.clone(), badFloor = floor.clone();
			if (corruptSurface) badSurface[2]--; else badFloor[2]--;
			var bad = new CompleteTerrainData(-64, height, choices, badSurface, badFloor, offsets, new byte[32]);
			assertThrows(IllegalArgumentException.class, () -> CompleteTerrainApplicator.prepareColumns(bad, surfacePredicate, floorPredicate));
		}
	}
}
