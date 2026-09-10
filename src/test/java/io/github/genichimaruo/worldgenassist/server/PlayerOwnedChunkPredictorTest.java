package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.status.ChunkPyramid;

import org.junit.jupiter.api.Test;

class PlayerOwnedChunkPredictorTest {
	private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	@Test
	void waitsForMovementThenPredictsBeyondEffectiveViewDistance() {
		PlayerOwnedChunkPredictor predictor = new PlayerOwnedChunkPredictor();

		assertTrue(predictor.observe(OWNER, OVERWORLD, 10, 20, 8, 1).isEmpty());
		PlayerOwnedChunkPredictor.Prediction prediction = predictor.observe(OWNER, OVERWORLD, 11, 19, 8, 1).orElseThrow();

		assertEquals(20, prediction.chunkX());
		assertEquals(10, prediction.chunkZ());
		assertEquals(1, prediction.directionX());
		assertEquals(-1, prediction.directionZ());
		assertEquals(8, prediction.effectiveViewDistance());
	}

	@Test
	void retainsDirectionWhileStationarySoBackpressureCanRetry() {
		PlayerOwnedChunkPredictor predictor = new PlayerOwnedChunkPredictor();
		predictor.observe(OWNER, OVERWORLD, 0, 0, 4, 2);
		predictor.observe(OWNER, OVERWORLD, 1, 0, 4, 2);

		PlayerOwnedChunkPredictor.Prediction retried = predictor.observe(OWNER, OVERWORLD, 1, 0, 4, 2).orElseThrow();

		assertEquals(7, retried.chunkX());
		assertEquals(0, retried.chunkZ());
	}

	@Test
	void resetsDirectionOnDimensionChangeOrTeleportSizedJump() {
		PlayerOwnedChunkPredictor predictor = new PlayerOwnedChunkPredictor();
		predictor.observe(OWNER, OVERWORLD, 0, 0, 4, 1);

		assertTrue(predictor.observe(OWNER, OVERWORLD, 8, 0, 4, 1).isEmpty());
		assertTrue(predictor.observe(OWNER, Identifier.parse("minecraft:the_nether"), 9, 0, 4, 1).isEmpty());
	}

	@Test
	void refusesPredictionBeyondMinecraftChunkBounds() {
		PlayerOwnedChunkPredictor predictor = new PlayerOwnedChunkPredictor();
		int edge = ChunkPyramid.MAX_CHUNK_COORDINATE_VALUE;
		predictor.observe(OWNER, OVERWORLD, edge - 1, 0, 2, 1);

		assertTrue(predictor.observe(OWNER, OVERWORLD, edge, 0, 2, 1).isEmpty());
	}

	@Test
	void computesMinecraftEffectiveViewDistanceBounds() {
		assertEquals(2, PlayerOwnedChunkPredictor.effectiveViewDistance(1, 1));
		assertEquals(7, PlayerOwnedChunkPredictor.effectiveViewDistance(12, 7));
		assertEquals(32, PlayerOwnedChunkPredictor.effectiveViewDistance(64, 64));
	}

	@Test
	void advancesAlongTheOwnedDirectionWhenNearerChunksAlreadyExist() {
		PlayerOwnedChunkPredictor predictor = new PlayerOwnedChunkPredictor();
		predictor.observe(OWNER, OVERWORLD, 0, 0, 3, 1);
		PlayerOwnedChunkPredictor.Prediction base = predictor.observe(OWNER, OVERWORLD, 1, 0, 3, 1).orElseThrow();

		PlayerOwnedChunkPredictor.Prediction advanced = PlayerOwnedChunkPredictor.advance(base, 4).orElseThrow();

		assertEquals(9, advanced.chunkX());
		assertEquals(0, advanced.chunkZ());
		assertThrows(
			IllegalArgumentException.class,
			() -> PlayerOwnedChunkPredictor.advance(base, PlayerOwnedChunkPredictor.MAX_UNLOADED_SCAN_CHUNKS + 1)
		);
	}

	@Test
	void clearsPerOwnerStateAndRejectsInvalidInputs() {
		PlayerOwnedChunkPredictor predictor = new PlayerOwnedChunkPredictor();
		predictor.observe(OWNER, OVERWORLD, 0, 0, 4, 1);
		assertEquals(1, predictor.trackedPlayers());

		predictor.remove(OWNER);
		assertEquals(0, predictor.trackedPlayers());
		assertThrows(IllegalArgumentException.class, () -> predictor.observe(new UUID(0L, 0L), OVERWORLD, 0, 0, 4, 1));
		assertThrows(IllegalArgumentException.class, () -> predictor.observe(OWNER, OVERWORLD, 0, 0, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> predictor.observe(OWNER, OVERWORLD, 0, 0, 4, 0));
	}
}
