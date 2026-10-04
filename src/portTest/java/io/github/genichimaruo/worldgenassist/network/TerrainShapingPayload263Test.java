package io.github.genichimaruo.worldgenassist.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import io.github.genichimaruo.worldgenassist.common.*;

class TerrainShapingPayload263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static TerrainDensityJob job(TerrainWorkKind kind, TerrainBeardifierData shaping) {
		var dim = Identifier.withDefaultNamespace("overworld");
		return new TerrainDensityJob(new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), dim,
			-2, 1, WorldgenContextFingerprint.fromBytes(new byte[32])), 8675309, true, dim, -64, 16, 1, 1, kind, shaping);
	}
	@Test void boundedShapingSurvivesMixedBatchAndInvalidLengthIsRejectedBeforeReadingBody() {
		var box = new TerrainBeardifierData.Box(-40, 30, -20, -5, 90, 50);
		var shaping = new TerrainBeardifierData(List.of(new TerrainBeardifierData.Rigid(box, 2, 1)),
			List.of(new TerrainBeardifierData.Junction(-20, 60, 20)), box);
		var complete = job(TerrainWorkKind.COMPLETE_TERRAIN, shaping);
		var batch = new TerrainJobBatchPayload(List.of(complete, job(TerrainWorkKind.COMPLETE_TERRAIN, TerrainBeardifierData.EMPTY),
			job(TerrainWorkKind.BLOCK_DENSITY_AND_SURFACE, TerrainBeardifierData.EMPTY), job(TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE, TerrainBeardifierData.EMPTY)));
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			TerrainJobBatchPayload.CODEC.encode(buffer, batch);
			assertEquals(batch, TerrainJobBatchPayload.CODEC.decode(buffer)); assertEquals(0, buffer.readableBytes());
			buffer.clear(); WorldgenPayloadCodecs.writeJob(buffer, complete);
			int lengthOffset = buffer.writerIndex() - shaping.encode().length - 1; // This body has a one-byte length.
			for (int invalid : new int[]{-1, 0, 12, TerrainBeardifierData.MAX_BYTES + 1}) {
				buffer.setIndex(0, lengthOffset); buffer.writeVarInt(invalid); int end = buffer.writerIndex();
				assertThrows(IllegalArgumentException.class, () -> WorldgenPayloadCodecs.readJob(buffer));
				assertEquals(end, buffer.readerIndex());
			}
			assertEquals(12, WorldgenProtocolVersion.CURRENT.value());
			assertThrows(IllegalArgumentException.class, () -> new WorldgenProtocolVersion(11).requireSupported());
		} finally { buffer.release(); }
	}
}
