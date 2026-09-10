package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

class RemoteDensityValidatorTest {
	@Test
	void selectsRequestedUniqueCellsInStableOrder() {
		int[] selected = RemoteDensityValidator.selectCells(768, 64, new Random(0xCA7L));

		assertEquals(64, selected.length);
		assertEquals(64, new HashSet<>(java.util.Arrays.stream(selected).boxed().toList()).size());
		for (int index = 0; index < selected.length; index++) {
			assertTrue(selected[index] >= 0 && selected[index] < 768);
			if (index > 0) {
				assertTrue(selected[index - 1] < selected[index]);
			}
		}
	}

	@Test
	void zeroSamplesSelectsNoCellsWithoutConsumingRandomness() {
		Random random = new Random(123L);
		int[] selected = RemoteDensityValidator.selectCells(10, 0, random);

		assertArrayEquals(new int[0], selected);
		assertEquals(new Random(123L).nextInt(), random.nextInt());
	}

	@Test
	void rejectsInvalidSelectionBounds() {
		assertThrows(IllegalArgumentException.class, () -> RemoteDensityValidator.selectCells(0, 0, new Random()));
		assertThrows(IllegalArgumentException.class, () -> RemoteDensityValidator.selectCells(10, -1, new Random()));
		assertThrows(IllegalArgumentException.class, () -> RemoteDensityValidator.selectCells(10, 11, new Random()));
	}

	@Test
	void exactComparisonRejectsAnyBitDifferentFiniteValue() {
		assertDoesNotThrow(() -> RemoteDensityValidator.requireExact(1.25, 1.25, 9));
		assertThrows(
			RemoteDensityValidator.RemoteDensityValidationException.class,
			() -> RemoteDensityValidator.requireExact(1.25, Math.nextUp(1.25), 9)
		);
		assertThrows(
			RemoteDensityValidator.RemoteDensityValidationException.class,
			() -> RemoteDensityValidator.requireExact(0.0, -0.0, 10)
		);
	}

	@Test
	void validationMetricsRejectNegativeValues() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteDensityValidator.ValidationMetrics(-1, 0, 0L)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteDensityValidator.ValidationMetrics(0, -1, 0L)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteDensityValidator.ValidationMetrics(0, 0, -1L)
		);
	}
}
