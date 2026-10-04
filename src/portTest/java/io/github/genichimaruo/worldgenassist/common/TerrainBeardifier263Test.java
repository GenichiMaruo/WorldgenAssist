package io.github.genichimaruo.worldgenassist.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

class TerrainBeardifier263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void reconstructedSamplerMatchesOriginalOrderedInputsIncludingBoundaries() {
		var rigids = new ArrayList<Beardifier.Rigid>();
		for (var adjustment : TerrainAdjustment.values()) rigids.add(new Beardifier.Rigid(
			new BoundingBox(-5, 55, -7, 8, 74, 6), adjustment, -2));
		rigids.add(rigids.get(2)); // Duplicate contributions and order must survive transport.
		var junctions = List.of(new JigsawJunction(2, 61, -3, 8, StructureTemplatePool.Projection.TERRAIN_MATCHING),
			new JigsawJunction(-4, 58, 4, -3, StructureTemplatePool.Projection.RIGID));
		var affected = new BoundingBox(-29, 31, -31, 32, 98, 30);
		var original = new Beardifier(rigids, junctions, affected);
		var data = TerrainBeardifierData.fromOriginalInputs(rigids, junctions, affected);
		var decoded = TerrainBeardifierData.decode(data.encode());
		assertEquals(data, decoded);
		var restored = decoded.sampler();
		boolean nonzero = false;
		for (int z = -32; z <= 31; z += 3) for (int x = -30; x <= 33; x += 3) for (int y = 30; y <= 99; y++) {
			float expected = original.sampleValue(null, x, y, z);
			assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(restored.sampleValue(null, x, y, z)));
			nonzero |= expected != 0;
		}
		assertTrue(nonzero);
		assertSame(Beardifier.EMPTY, TerrainBeardifierData.decode(TerrainBeardifierData.EMPTY.encode()).sampler());
		assertSame(TerrainBeardifierData.EMPTY, TerrainBeardifierData.capture(Beardifier.EMPTY));
	}
	@Test void snapshotsAreImmutableAndMalformedOrExcessiveBodiesAreRefused() {
		var box = new BoundingBox(0, 50, 0, 10, 70, 10);
		var pieces = new ArrayList<>(List.of(new Beardifier.Rigid(box, TerrainAdjustment.BEARD_BOX, 2)));
		var data = TerrainBeardifierData.fromOriginalInputs(pieces, List.of(), box);
		byte[] encoded = data.encode();
		box.move(100, 0, 0); pieces.clear();
		assertEquals(0, data.pieces().getFirst().box().minX());
		assertArrayEquals(encoded, data.encode());
		assertThrows(UnsupportedOperationException.class, () -> data.pieces().clear());
		var rigid = data.pieces().getFirst(); var junction = new TerrainBeardifierData.Junction(1, 60, 1);
		var maximum = new TerrainBeardifierData(Collections.nCopies(64, rigid), Collections.nCopies(256, junction), data.affectedBox());
		assertEquals(TerrainBeardifierData.MAX_BYTES, maximum.encode().length);
		assertEquals(maximum, TerrainBeardifierData.decode(maximum.encode()));
		assertThrows(IllegalArgumentException.class, () -> new TerrainBeardifierData(Collections.nCopies(65, rigid), List.of(), data.affectedBox()));
		assertThrows(IllegalArgumentException.class, () -> new TerrainBeardifierData(List.of(), Collections.nCopies(257, junction), data.affectedBox()));
		assertThrows(IllegalArgumentException.class, () -> new TerrainBeardifierData(List.of(), List.of(), data.affectedBox()));
		for (int length : new int[]{0, 12, encoded.length - 1, encoded.length + 1, TerrainBeardifierData.MAX_BYTES + 1}) {
			assertThrows(IllegalArgumentException.class, () -> TerrainBeardifierData.decode(Arrays.copyOf(encoded, length)));
		}
		for (int offset : new int[]{0, 4, 8}) {
			byte[] changed = encoded.clone(); ByteBuffer.wrap(changed).putInt(offset, Integer.MAX_VALUE);
			assertThrows(IllegalArgumentException.class, () -> TerrainBeardifierData.decode(changed));
		}
		byte[] wrongAdjustment = encoded.clone(); wrongAdjustment[61] = (byte)255;
		assertThrows(IllegalArgumentException.class, () -> TerrainBeardifierData.decode(wrongAdjustment));
		assertThrows(IllegalArgumentException.class, () -> new TerrainBeardifierData.Box(2, 0, 0, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new TerrainBeardifierData.Box(-32_000_001, 0, 0, 0, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new TerrainBeardifierData.Rigid(rigid.box(), 0, 4097));
		assertThrows(IllegalArgumentException.class, () -> new TerrainBeardifierData.Junction(0, 32768, 0));
	}
}
