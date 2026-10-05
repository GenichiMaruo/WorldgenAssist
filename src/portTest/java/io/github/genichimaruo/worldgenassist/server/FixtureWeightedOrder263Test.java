package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import org.junit.jupiter.api.Test;

class FixtureWeightedOrder263Test {
	@Test void registryOrderKeepsWeightsAndRandomConsumptionAcrossInputPermutations() {
		var skeleton = new Weighted<>("skeleton",1); var spider = new Weighted<>("spider",1); var zombie = new Weighted<>("zombie",2);
		var original = WeightedList.of(List.of(zombie,spider,skeleton));
		var a = FixtureWeightedOrder.canonical(original, value -> value);
		var b = FixtureWeightedOrder.canonical(WeightedList.of(List.of(spider,skeleton,zombie)), value -> value);
		assertEquals(List.of(skeleton,spider,zombie),a.unwrap());assertEquals(a.unwrap(),b.unwrap());
		assertEquals(List.of(zombie,spider,skeleton),original.unwrap());
		var ra = RandomSource.create(8675309);var rb = RandomSource.create(8675309);var expected = RandomSource.create(8675309);
		for (int index=0;index<128;index++) {
			String selected = List.of("skeleton","spider","zombie","zombie").get(expected.nextInt(4));
			assertEquals(selected,a.getRandomOrThrow(ra));assertEquals(selected,b.getRandomOrThrow(rb));
		}
		long next = expected.nextLong();assertEquals(next,ra.nextLong());assertEquals(next,rb.nextLong());
	}
}
