package io.github.genichimaruo.worldgenassist.common;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Versioned server-owned choices. No registry IDs or palettes supplied by a worker. */
public final class CompleteTerrainPalette {
	public static final int VERSION = 1;
	private static final String[] NAMES = {
		"air", "cave_air", "stone", "deepslate", "bedrock", "water", "lava",
		"grass_block", "dirt", "coarse_dirt", "podzol", "mycelium", "gravel",
		"sand", "red_sand", "sandstone", "red_sandstone", "terracotta",
		"white_terracotta", "orange_terracotta", "magenta_terracotta", "light_blue_terracotta",
		"yellow_terracotta", "lime_terracotta", "pink_terracotta", "gray_terracotta",
		"light_gray_terracotta", "cyan_terracotta", "purple_terracotta", "blue_terracotta",
		"brown_terracotta", "green_terracotta", "red_terracotta", "black_terracotta",
		"ice", "packed_ice", "blue_ice", "snow_block", "powder_snow", "calcite", "tuff",
		"granite", "diorite", "andesite", "iron_ore", "deepslate_iron_ore", "copper_ore",
		"deepslate_copper_ore", "raw_iron_block", "raw_copper_block"
	};
	private final BlockState[] states;
	private final Map<BlockState, Integer> codes = new IdentityHashMap<>();

	public CompleteTerrainPalette() {
		states = new BlockState[NAMES.length];
		for (int i = 0; i < NAMES.length; i++) {
			Identifier id = Identifier.withDefaultNamespace(NAMES[i]);
			Block block = BuiltInRegistries.BLOCK.getValue(id);
			if (block == null || !id.equals(BuiltInRegistries.BLOCK.getKey(block))) {
				throw new IllegalArgumentException("Missing terrain palette member: " + id);
			}
			BlockState state = block.defaultBlockState();
			if (state.hasBlockEntity()) throw new IllegalArgumentException("Block entity in terrain palette");
			states[i] = state;
			if (codes.put(state, i) != null) throw new IllegalArgumentException("Duplicate terrain palette state");
		}
	}

	public int size() { return states.length; }
	public byte encode(BlockState state) {
		Integer code = codes.get(state);
		if (code == null) throw new IllegalArgumentException("State outside terrain palette: " + state);
		return (byte)(int)code;
	}
	public BlockState state(int code) {
		if (code < 0 || code >= states.length) throw new IllegalArgumentException("Invalid terrain palette code");
		return states[code];
	}
	public static boolean validCode(int code) { return code >= 0 && code < NAMES.length; }
}
