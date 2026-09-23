package io.github.genichimaruo.worldgenassist.common;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/** Explicit vanilla-dimension scope; custom generators still need eligibility checks. */
public final class SupportedDimensions {
	private SupportedDimensions() { }

	public static boolean contains(Identifier dimension) {
		return Level.OVERWORLD.identifier().equals(dimension)
			|| Level.NETHER.identifier().equals(dimension)
			|| Level.END.identifier().equals(dimension);
	}
}
