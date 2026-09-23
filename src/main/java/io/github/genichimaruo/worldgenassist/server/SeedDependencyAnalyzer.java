package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

/**
 * Read-only inventory of seed-dependent leaves reachable from a density graph.
 *
 * <p>This is deliberately not a serializer. In particular, it never extracts
 * initialized noise state, permutation tables, random factories, or a seed.
 * Its output is safe metadata used to define the boundary of a future
 * server-local recorder/replayer.</p>
 */
public final class SeedDependencyAnalyzer {
	private SeedDependencyAnalyzer() {
	}

	public static Report analyze(DensityFunction root) {
		Objects.requireNonNull(root, "root");
		InventoryVisitor visitor = new InventoryVisitor();
		root.mapAll(visitor);
		return visitor.report();
	}

	public record Report(
		List<Identifier> keyedNoises,
		List<String> functionTypes,
		int noiseHolderReferences,
		int unkeyedNoiseHolderReferences,
		int unwiredNoiseHolderReferences,
		int blendedNoiseNodes
	) {
		public Report {
			keyedNoises = List.copyOf(keyedNoises);
			functionTypes = List.copyOf(functionTypes);
			if (noiseHolderReferences < 0
				|| unkeyedNoiseHolderReferences < 0
				|| unwiredNoiseHolderReferences < 0
				|| blendedNoiseNodes < 0) {
				throw new IllegalArgumentException("Seed-dependency counts cannot be negative");
			}
			if (unkeyedNoiseHolderReferences > noiseHolderReferences
				|| unwiredNoiseHolderReferences > noiseHolderReferences) {
				throw new IllegalArgumentException("Noise-holder subsets cannot exceed the total reference count");
			}
		}

		public boolean hasSeedDependentLeaves() {
			return noiseHolderReferences > 0 || blendedNoiseNodes > 0;
		}

		public boolean fullyKeyedAndWired() {
			return noiseHolderReferences > 0
				&& unkeyedNoiseHolderReferences == 0
				&& unwiredNoiseHolderReferences == 0;
		}
	}

	private static final class InventoryVisitor implements DensityFunction.Visitor {
		private final Set<DensityFunction> visitedFunctions = Collections.newSetFromMap(new IdentityHashMap<>());
		private final Set<Identifier> keyedNoises = new LinkedHashSet<>();
		private final Set<String> functionTypes = new LinkedHashSet<>();
		private int noiseHolderReferences;
		private int unkeyedNoiseHolderReferences;
		private int unwiredNoiseHolderReferences;
		private int blendedNoiseNodes;

		@Override
		public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
			noiseHolderReferences++;
			noise.noiseData().unwrapKey().ifPresentOrElse(
				key -> keyedNoises.add(key.identifier()),
				() -> unkeyedNoiseHolderReferences++
			);
			if (noise.noise() == null) {
				unwiredNoiseHolderReferences++;
			}
			return noise;
		}

		@Override
		public DensityFunction apply(DensityFunction input) {
			if (visitedFunctions.add(input)) {
				functionTypes.add(input.getClass().getName());
				if (input instanceof BlendedNoise) {
					blendedNoiseNodes++;
				}
			}
			return input;
		}

		private Report report() {
			List<Identifier> noises = new ArrayList<>(keyedNoises);
			noises.sort((left, right) -> left.toString().compareTo(right.toString()));
			List<String> types = new ArrayList<>(functionTypes);
			types.sort(String::compareTo);
			return new Report(
				noises,
				types,
				noiseHolderReferences,
				unkeyedNoiseHolderReferences,
				unwiredNoiseHolderReferences,
				blendedNoiseNodes
			);
		}
	}
}
