package io.github.genichimaruo.worldgenassist.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/** Immutable bounded terrain choices, exact WG heightmaps and ordered fluid offsets. */
public final class CompleteTerrainData {
	public static final int MAX_POST_PROCESS_PER_BLOCK = 4;
	public static final int MAX_SECTIONS = TerrainDensityJob.MAX_HEIGHT / 16;
	public static final int BODY_VERSION = 2;
	public static final int MAX_RAW_BYTES = 48 + TerrainDensityJob.MAX_SAMPLE_COUNT
		+ 1024 + MAX_SECTIONS * 4 + TerrainDensityJob.MAX_SAMPLE_COUNT * MAX_POST_PROCESS_PER_BLOCK * 2
		+ 5 + CompleteBiomeData.MAX_BYTES;
	private final int minY;
	private final int height;
	private final byte[] choices;
	private final short[] surfaceHeights;
	private final short[] floorHeights;
	private final short[][] postProcessing;
	private final byte[] biomeWindowDigest;
	private final CompleteBiomeData centerBiomes;
	// Pure derived representation only. Never serialized or used as approval.
	private volatile CompleteTerrainSectionStates sectionStates;

	public CompleteTerrainData(int minY, int height, byte[] choices, short[] surfaceHeights,
		short[] floorHeights, short[][] postProcessing, byte[] biomeWindowDigest) {
		this(minY, height, choices, surfaceHeights, floorHeights, postProcessing, biomeWindowDigest, null);
	}
	public CompleteTerrainData(int minY, int height, byte[] choices, short[] surfaceHeights,
		short[] floorHeights, short[][] postProcessing, byte[] biomeWindowDigest, CompleteBiomeData centerBiomes) {
		if (height <= 0 || height > TerrainDensityJob.MAX_HEIGHT || height % 16 != 0 || minY % 16 != 0) {
			throw new IllegalArgumentException("Unaligned complete terrain geometry");
		}
		Math.addExact(minY, height);
		Objects.requireNonNull(choices); Objects.requireNonNull(surfaceHeights);
		Objects.requireNonNull(floorHeights); Objects.requireNonNull(postProcessing);
		if (Objects.requireNonNull(biomeWindowDigest).length != 32) throw new IllegalArgumentException("Invalid biome digest");
		this.biomeWindowDigest = biomeWindowDigest.clone();
		if (centerBiomes != null && (centerBiomes.minY() != minY || centerBiomes.height() != height)) {
			throw new IllegalArgumentException("Center biomes differ from terrain geometry");
		}
		this.centerBiomes = centerBiomes;
		if (choices.length != 256 * height || surfaceHeights.length != 256 || floorHeights.length != 256
			|| postProcessing.length != height / 16) throw new IllegalArgumentException("Incomplete terrain shape");
		this.minY = minY; this.height = height; this.choices = choices.clone();
		for (byte code : this.choices) if (!CompleteTerrainPalette.validCode(Byte.toUnsignedInt(code))) {
			throw new IllegalArgumentException("Invalid terrain palette choice");
		}
		this.surfaceHeights = requireHeights(surfaceHeights, height);
		this.floorHeights = requireHeights(floorHeights, height);
		this.postProcessing = new short[postProcessing.length][];
		int remaining = choices.length * MAX_POST_PROCESS_PER_BLOCK;
		for (int section = 0; section < postProcessing.length; section++) {
			short[] offsets = Objects.requireNonNull(postProcessing[section]);
			if (offsets.length > remaining) throw new IllegalArgumentException("Excessive fluid postprocessing");
			remaining -= offsets.length;
			this.postProcessing[section] = offsets.clone();
			for (short offset : this.postProcessing[section]) if (Short.toUnsignedInt(offset) >= 4096) {
				throw new IllegalArgumentException("Postprocessing offset outside section");
			}
		}
	}

	private static short[] requireHeights(short[] heights, int height) {
		short[] copy = heights.clone();
		for (short value : copy) if (value < 0 || value > height) throw new IllegalArgumentException("Height outside terrain");
		return copy;
	}
	public int minY() { return minY; }
	public int height() { return height; }
	public int blockCount() { return choices.length; }
	/** Order: (z * 16 + x) * height + (y - minY), matching density volumes. */
	public int choice(int index) { return Byte.toUnsignedInt(choices[index]); }
	public short[] surfaceHeights() { return surfaceHeights.clone(); }
	public short[] floorHeights() { return floorHeights.clone(); }
	public short[] postProcessing(int section) { return postProcessing[section].clone(); }
	public byte[] biomeWindowDigest() { return biomeWindowDigest.clone(); }
	public CompleteBiomeData centerBiomes() { return centerBiomes; }
	public boolean hasPreparedSections() { return sectionStates != null; }
	/** Server decoder or generation worker; bounded by the result's existing lifetime. */
	public CompleteTerrainSectionStates prepareSections() {
		CompleteTerrainSectionStates prepared = sectionStates;
		if (prepared != null) return prepared;
		synchronized (this) {
			if (sectionStates == null) sectionStates = CompleteTerrainSectionStates.prepare(this);
			return sectionStates;
		}
	}

	public byte[] encode() {
		byte[] biomes = centerBiomes == null ? null : centerBiomes.encode();
		int length = 48 + choices.length + 1024 + postProcessing.length * 4 + 1 + (biomes == null ? 0 : 4 + biomes.length);
		for (short[] offsets : postProcessing) length = Math.addExact(length, offsets.length * 2);
		ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
		out.putInt(BODY_VERSION).putInt(minY).putInt(height).putInt(choices.length)
			.put(biomeWindowDigest).put(choices);
		for (short value : surfaceHeights) out.putShort(value);
		for (short value : floorHeights) out.putShort(value);
		for (short[] offsets : postProcessing) {
			out.putInt(offsets.length);
			for (short offset : offsets) out.putShort(offset);
		}
		out.put((byte)(biomes == null ? 0 : 1));
		if (biomes != null) out.putInt(biomes.length).put(biomes);
		return out.array();
	}

	public static CompleteTerrainData decode(byte[] bytes, int expectedMinY, int expectedHeight) {
		Objects.requireNonNull(bytes);
		if (bytes.length < 48 || bytes.length > MAX_RAW_BYTES) throw new IllegalArgumentException("Invalid terrain bytes");
		ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		int version = in.getInt(), minY = in.getInt(), height = in.getInt(), count = in.getInt();
		// Version 1 remains readable for retained offline captures; live jobs use protocol13.
		if ((version != 1 && version != BODY_VERSION) || minY != expectedMinY || height != expectedHeight
			|| height <= 0 || height > TerrainDensityJob.MAX_HEIGHT || height % 16 != 0 || minY % 16 != 0
			|| count != 256 * height || in.remaining() < 32 + count + 1024 + height / 16 * 4) {
			throw new IllegalArgumentException("Terrain header differs from assigned geometry");
		}
		byte[] biomeDigest = new byte[32]; in.get(biomeDigest);
		byte[] choices = new byte[count]; in.get(choices);
		short[] surface = new short[256], floor = new short[256];
		for (int i = 0; i < 256; i++) surface[i] = in.getShort();
		for (int i = 0; i < 256; i++) floor[i] = in.getShort();
		short[][] offsets = new short[height / 16][];
		int remaining = count * MAX_POST_PROCESS_PER_BLOCK;
		for (int section = 0; section < offsets.length; section++) {
			if (in.remaining() < 4) throw new IllegalArgumentException("Truncated postprocessing counts");
			int size = in.getInt();
			if (size < 0 || size > remaining || size > in.remaining() / 2) {
				throw new IllegalArgumentException("Excessive or truncated postprocessing offsets");
			}
			remaining -= size;
			offsets[section] = new short[size];
			for (int i = 0; i < size; i++) offsets[section][i] = in.getShort();
		}
		CompleteBiomeData center = null;
		if (version == BODY_VERSION) {
			if (!in.hasRemaining()) throw new IllegalArgumentException("Missing center biome flag");
			int flag = Byte.toUnsignedInt(in.get());
			if (flag > 1) throw new IllegalArgumentException("Invalid center biome flag");
			if (flag == 1) {
				if (in.remaining() < 4) throw new IllegalArgumentException("Missing center biome length");
				int size = in.getInt();
				if (size < 14 || size > CompleteBiomeData.MAX_BYTES || size > in.remaining()) throw new IllegalArgumentException("Invalid center biome bound");
				byte[] body = new byte[size]; in.get(body); center = CompleteBiomeData.decode(body);
			}
		}
		if (in.hasRemaining()) throw new IllegalArgumentException("Trailing terrain bytes");
		return new CompleteTerrainData(minY, height, choices, surface, floor, offsets, biomeDigest, center);
	}

	/** Exact terrain agreement; provenance is selected against server inputs separately. */
	public boolean sameTerrainAs(CompleteTerrainData data) {
		return data != null && minY == data.minY && height == data.height
			&& Arrays.equals(choices, data.choices) && Arrays.equals(surfaceHeights, data.surfaceHeights)
			&& Arrays.equals(floorHeights, data.floorHeights) && Arrays.deepEquals(postProcessing, data.postProcessing);
	}
	@Override public boolean equals(Object other) {
		return other instanceof CompleteTerrainData data && sameTerrainAs(data) && Arrays.equals(biomeWindowDigest,data.biomeWindowDigest)
			&& Objects.equals(centerBiomes, data.centerBiomes);
	}
	@Override public int hashCode() {
		int hash = Objects.hash(minY, height);
		hash = 31 * hash + Arrays.hashCode(choices);
		hash = 31 * hash + Arrays.hashCode(surfaceHeights);
		hash = 31 * hash + Arrays.hashCode(floorHeights);
		hash = 31 * hash + Arrays.deepHashCode(postProcessing);
		return 31 * (31 * hash + Arrays.hashCode(biomeWindowDigest)) + Objects.hashCode(centerBiomes);
	}
}
