package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerrainDensityJobTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void acceptsTheBoundedOverworldShape() {
		TerrainDensityJob job = job(-64, TerrainDensityJob.MAX_HEIGHT, 4, 8);

		assertEquals(98_304, job.sampleCount());
	}

	@Test
	void rejectsInvalidHeightAndCellGeometry() {
		assertThrows(IllegalArgumentException.class, () -> job(-64, 0, 4, 8));
		assertThrows(IllegalArgumentException.class, () -> job(-64, TerrainDensityJob.MAX_HEIGHT + 1, 4, 8));
		assertThrows(IllegalArgumentException.class, () -> job(-64, 384, 3, 8));
		assertThrows(IllegalArgumentException.class, () -> job(-64, 383, 4, 8));
		assertThrows(IllegalArgumentException.class, () -> job(-63, 384, 4, 8));
	}

	@Test
	void densityResultIsBoundedFiniteAndDefensivelyCopied() {
		double[] densities = {0.25, -0.5};
		TerrainDensityResult result = new TerrainDensityResult(identity(), densities, 10L);
		densities[0] = 99.0;
		double[] copy = result.densities();
		copy[1] = 99.0;

		assertEquals(0.25, result.densityAt(0));
		assertEquals(-0.5, result.densityAt(1));
		assertNotSame(copy, result.densities());
		assertThrows(IllegalArgumentException.class, () -> new TerrainDensityResult(identity(), new double[0], 0L));
		assertThrows(IllegalArgumentException.class, () -> new TerrainDensityResult(identity(), new double[] {Double.NaN}, 0L));
		assertThrows(IllegalArgumentException.class, () -> new TerrainDensityResult(identity(), new double[] {Double.POSITIVE_INFINITY}, 0L));
		assertThrows(
			IllegalArgumentException.class,
			() -> new TerrainDensityResult(identity(), new double[] {TerrainDensityResult.MAX_ABSOLUTE_DENSITY + 1.0}, 0L)
		);
		assertThrows(IllegalArgumentException.class, () -> new TerrainDensityResult(identity(), new double[] {0.0}, -1L));
	}

	private static TerrainDensityJob job(int minY, int height, int cellWidth, int cellHeight) {
		return new TerrainDensityJob(
			identity(),
			8675309L,
			true,
			Identifier.parse("minecraft:overworld"),
			minY,
			height,
			cellWidth,
			cellHeight
		);
	}

	private static TerrainJobIdentity identity() {
		return new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			Identifier.parse("minecraft:overworld"),
			0,
			0,
			WorldgenContextFingerprint.fromHex("11".repeat(32))
		);
	}
}
