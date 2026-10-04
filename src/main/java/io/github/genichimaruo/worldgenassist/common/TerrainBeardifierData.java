package io.github.genichimaruo.worldgenassist.common;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/** Immutable server-issued shaping inputs, never structure placement or client mutation instructions. */
public record TerrainBeardifierData(List<Rigid> pieces, List<Junction> junctions, Box affectedBox) {
	public static final int MAX_PIECES = 64, MAX_JUNCTIONS = 256;
	public static final int MAX_BYTES = 13 + 24 + MAX_PIECES * 29 + MAX_JUNCTIONS * 12;
	public static final TerrainBeardifierData EMPTY = new TerrainBeardifierData(List.of(), List.of(), null);
	public TerrainBeardifierData {
		Objects.requireNonNull(pieces); Objects.requireNonNull(junctions);
		if (pieces.size() > MAX_PIECES || junctions.size() > MAX_JUNCTIONS) throw new IllegalArgumentException("Too many shaping inputs");
		pieces = List.copyOf(pieces); junctions = List.copyOf(junctions);
		if ((pieces.isEmpty() && junctions.isEmpty()) != (affectedBox == null)) throw new IllegalArgumentException("Invalid shaping affected box");
	}
	public boolean empty() { return affectedBox == null; }
	public static TerrainBeardifierData capture(Beardifier sampler) {
		Objects.requireNonNull(sampler);
		if (sampler == Beardifier.EMPTY) return EMPTY;
		if (sampler.getClass() != Beardifier.class || !(sampler instanceof TerrainBeardifierAccess access)) {
			throw new IllegalArgumentException("Unsupported shaping sampler or missing accessor");
		}
		return fromOriginalInputs(access.worldgenAssist$rigids(), access.worldgenAssist$junctions(), access.worldgenAssist$affectedBox());
	}
	/** Copy mutable game bounding boxes immediately; preserve the actual traversal order. */
	public static TerrainBeardifierData fromOriginalInputs(List<Beardifier.Rigid> pieces, List<JigsawJunction> junctions, BoundingBox affected) {
		if (pieces.size() > MAX_PIECES || junctions.size() > MAX_JUNCTIONS) throw new IllegalArgumentException("Too many shaping inputs");
		return new TerrainBeardifierData(pieces.stream().map(piece -> new Rigid(Box.copy(piece.box()),
			piece.terrainAdjustment().ordinal(), piece.groundLevelDelta())).toList(),
			junctions.stream().map(junction -> new Junction(junction.getSourceX(), junction.getSourceGroundY(), junction.getSourceZ())).toList(),
			affected == null ? null : Box.copy(affected));
	}
	public Beardifier sampler() {
		if (empty()) return Beardifier.EMPTY;
		return new Beardifier(pieces.stream().map(piece -> new Beardifier.Rigid(piece.box().gameBox(),
			TerrainAdjustment.values()[piece.adjustment()], piece.groundDelta())).toList(),
			junctions.stream().map(junction -> new JigsawJunction(junction.x(), junction.y(), junction.z(), 0,
				StructureTemplatePool.Projection.RIGID)).toList(), affectedBox.gameBox());
	}
	public byte[] encode() {
		ByteBuffer buffer = ByteBuffer.allocate(encodedSize(pieces.size(), junctions.size(), !empty()));
		buffer.putInt(1).putInt(pieces.size()).putInt(junctions.size()).put((byte)(empty() ? 0 : 1));
		if (!empty()) affectedBox.write(buffer);
		for (Rigid piece : pieces) { piece.box().write(buffer); buffer.put((byte)piece.adjustment()).putInt(piece.groundDelta()); }
		for (Junction junction : junctions) buffer.putInt(junction.x()).putInt(junction.y()).putInt(junction.z());
		return buffer.array();
	}
	public static TerrainBeardifierData decode(byte[] encoded) {
		Objects.requireNonNull(encoded);
		if (encoded.length < 13 || encoded.length > MAX_BYTES) throw new IllegalArgumentException("Invalid shaping body length");
		ByteBuffer buffer = ByteBuffer.wrap(encoded);
		int version = buffer.getInt(), pieceCount = buffer.getInt(), junctionCount = buffer.getInt(), flag = Byte.toUnsignedInt(buffer.get());
		if (version != 1 || pieceCount < 0 || pieceCount > MAX_PIECES || junctionCount < 0 || junctionCount > MAX_JUNCTIONS
			|| flag > 1 || encoded.length != encodedSize(pieceCount, junctionCount, flag == 1)) {
			throw new IllegalArgumentException("Invalid shaping framing");
		}
		Box affected = flag == 1 ? Box.read(buffer) : null;
		var pieces = new java.util.ArrayList<Rigid>(pieceCount);
		var junctions = new java.util.ArrayList<Junction>(junctionCount);
		for (int i = 0; i < pieceCount; i++) pieces.add(new Rigid(Box.read(buffer), Byte.toUnsignedInt(buffer.get()), buffer.getInt()));
		for (int i = 0; i < junctionCount; i++) junctions.add(new Junction(buffer.getInt(), buffer.getInt(), buffer.getInt()));
		return new TerrainBeardifierData(pieces, junctions, affected);
	}
	private static int encodedSize(int pieces, int junctions, boolean box) { return 13 + (box ? 24 : 0) + pieces * 29 + junctions * 12; }
	private static void horizontal(int value) { if (value < -32_000_000 || value > 32_000_000) throw new IllegalArgumentException("Shaping horizontal bound"); }
	private static void vertical(int value) { if (value < -32768 || value > 32767) throw new IllegalArgumentException("Shaping vertical bound"); }
	public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public Box {
			horizontal(minX); horizontal(maxX); horizontal(minZ); horizontal(maxZ); vertical(minY); vertical(maxY);
			if (minX > maxX || minY > maxY || minZ > maxZ || (long)maxX - minX > 8192
				|| (long)maxY - minY > 8192 || (long)maxZ - minZ > 8192) throw new IllegalArgumentException("Invalid shaping box");
		}
		static Box copy(BoundingBox box) { return new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()); }
		BoundingBox gameBox() { return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ); }
		void write(ByteBuffer buffer) { buffer.putInt(minX).putInt(minY).putInt(minZ).putInt(maxX).putInt(maxY).putInt(maxZ); }
		static Box read(ByteBuffer buffer) { return new Box(buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt()); }
	}
	public record Rigid(Box box, int adjustment, int groundDelta) {
		public Rigid {
			Objects.requireNonNull(box);
			if (adjustment < 0 || adjustment >= TerrainAdjustment.values().length || groundDelta < -4096 || groundDelta > 4096) {
				throw new IllegalArgumentException("Invalid shaping rigid");
			}
		}
	}
	public record Junction(int x, int y, int z) {
		public Junction { horizontal(x); horizontal(z); vertical(y); }
	}
}
