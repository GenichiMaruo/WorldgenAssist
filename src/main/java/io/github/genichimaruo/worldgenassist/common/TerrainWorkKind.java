package io.github.genichimaruo.worldgenassist.common;

/** Protocol 8 adds bounded aquifer decision intermediates for block fill only. */
public enum TerrainWorkKind {
	DENSITY, SURFACE_FIELDS, GRID_AND_SURFACE, BLOCK_DENSITY_AND_SURFACE, TERRAIN_DECISIONS_AND_SURFACE,
	COMPLETE_TERRAIN;

	public static TerrainWorkKind fromWire(int value) {
		if (value < 0 || value >= values().length) throw new IllegalArgumentException("Unknown terrain work kind: " + value);
		return values()[value];
	}
}
