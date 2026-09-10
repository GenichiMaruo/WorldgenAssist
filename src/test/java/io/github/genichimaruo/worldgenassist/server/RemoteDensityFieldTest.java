package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityResult;
import io.github.genichimaruo.worldgenassist.common.TerrainJobIdentity;
import io.github.genichimaruo.worldgenassist.common.WorldgenContextFingerprint;
import io.github.genichimaruo.worldgenassist.common.WorldgenProtocolVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RemoteDensityFieldTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void copiesCanonicalDensityOrderIntoVanillaCellOrder() {
		TerrainDensityJob job = job(identity(), 8, 4, 8);
		double[] values = new double[job.sampleCount()];
		for (int index = 0; index < values.length; index++) {
			values[index] = index;
		}
		RemoteDensityField field = new RemoteDensityField(job, new TerrainDensityResult(job.identity(), values, 1L));
		double[] cell = new double[4 * 4 * 8];

		field.copyCell(1, 0, 2, cell);

		assertEquals(index(4, 7, 8), cell[0]);
		assertEquals(index(7, 0, 11), cell[cell.length - 1]);
		assertEquals(index(5, 3, 10), field.densityAtOffset(5, 3, 10));
	}

	@Test
	void rejectsWrongIdentityCountCellAndDestination() {
		TerrainDensityJob job = job(identity(), 8, 4, 8);
		double[] values = new double[job.sampleCount()];
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteDensityField(job, new TerrainDensityResult(identityWithChunk(1), values, 0L))
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RemoteDensityField(job, new TerrainDensityResult(job.identity(), new double[] {0.0}, 0L))
		);

		RemoteDensityField field = new RemoteDensityField(job, new TerrainDensityResult(job.identity(), values, 0L));
		assertThrows(IllegalArgumentException.class, () -> field.copyCell(4, 0, 0, new double[128]));
		assertThrows(IllegalArgumentException.class, () -> field.copyCell(0, 0, 0, new double[127]));
	}

	private static TerrainDensityJob job(TerrainJobIdentity identity, int height, int cellWidth, int cellHeight) {
		return new TerrainDensityJob(
			identity,
			8675309L,
			true,
			Identifier.parse("minecraft:overworld"),
			-64,
			height,
			cellWidth,
			cellHeight
		);
	}

	private static int index(int x, int y, int z) {
		return (y * 16 + x) * 16 + z;
	}

	private static TerrainJobIdentity identity() {
		return identityWithChunk(0);
	}

	private static TerrainJobIdentity identityWithChunk(int chunkX) {
		return new TerrainJobIdentity(
			WorldgenProtocolVersion.CURRENT,
			UUID.fromString("12345678-1234-1234-1234-123456789abc"),
			Identifier.parse("minecraft:overworld"),
			chunkX,
			0,
			WorldgenContextFingerprint.fromHex("cd".repeat(32))
		);
	}
}
