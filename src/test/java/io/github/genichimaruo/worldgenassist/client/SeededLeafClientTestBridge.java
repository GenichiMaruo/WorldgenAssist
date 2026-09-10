package io.github.genichimaruo.worldgenassist.client;

import java.util.Objects;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;

import io.github.genichimaruo.worldgenassist.common.AuthorizedSeededLeafJob;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResult;

/** Test-source-only bridge; production visibility remains package scoped. */
public final class SeededLeafClientTestBridge {
	private SeededLeafClientTestBridge() {
	}

	public static SeededLeafDensityResult compute(
		HolderLookup.Provider registries,
		Identifier dimension,
		AuthorizedSeededLeafJob authorization
	) {
		return SeededLeafClientDensityComputer.compute(
			Objects.requireNonNull(registries, "registries"),
			Objects.requireNonNull(dimension, "dimension"),
			Objects.requireNonNull(authorization, "authorization")
		);
	}
}
