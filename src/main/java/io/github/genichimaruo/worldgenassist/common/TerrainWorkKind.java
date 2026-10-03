package io.github.genichimaruo.worldgenassist.common;

/** Protocol 6 adds exact inputs to vanilla's five outer terrain interpolators. */
public enum TerrainWorkKind {
	DENSITY, SURFACE_FIELDS, GRID_AND_SURFACE;

	public static TerrainWorkKind fromWire(int value) {
		if (value < 0 || value >= values().length) throw new IllegalArgumentException("Unknown terrain work kind: " + value);
		return values()[value];
	}
}
