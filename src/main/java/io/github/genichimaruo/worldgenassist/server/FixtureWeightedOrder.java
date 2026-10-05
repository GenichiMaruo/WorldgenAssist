package io.github.genichimaruo.worldgenassist.server;

import java.util.Comparator;
import java.util.function.Function;
import net.minecraft.util.random.WeightedList;

/** Fixture-only registry order; preserves each original value/weight and the game's selector. */
public final class FixtureWeightedOrder {
	private FixtureWeightedOrder() {}
	public static <T> WeightedList<T> canonical(WeightedList<T> original, Function<T,String> key) {
		return WeightedList.of(original.unwrap().stream().sorted(Comparator.comparing(entry -> key.apply(entry.value()))).toList());
	}
}
