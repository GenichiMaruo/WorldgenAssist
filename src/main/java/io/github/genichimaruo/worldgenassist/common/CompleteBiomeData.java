package io.github.genichimaruo.worldgenassist.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** All center quart voxels and each original palette, including unused entries.
 * Names bind private byte codes; neither registry IDs nor live containers cross the wire. */
public final class CompleteBiomeData {
	public static final int MAX_NAMES = 128;
	public static final int MAX_NAME_BYTES = 128;
	public static final int MAX_BYTES = 14 + MAX_NAMES * (2 + MAX_NAME_BYTES)
		+ CompleteTerrainData.MAX_SECTIONS * (1 + MAX_NAMES + 64);
	private final int minY, height;
	private final List<String> names;
	private final byte[][] palettes;
	private final byte[] voxels;

	public CompleteBiomeData(int minY, int height, List<String> names, byte[][] palettes, byte[] voxels) {
		if (height < 16 || height > TerrainDensityJob.MAX_HEIGHT || height % 16 != 0 || minY % 16 != 0) {
			throw new IllegalArgumentException("Invalid complete biome geometry");
		}
		Math.addExact(minY, height);
		this.minY = minY; this.height = height; this.names = List.copyOf(names);
		if (this.names.isEmpty() || this.names.size() > MAX_NAMES || palettes.length != height / 16
			|| voxels.length != height * 4) throw new IllegalArgumentException("Invalid complete biome shape");
		String previous = null;
		for (String name : this.names) {
			if (!Identifier.parse(name).toString().equals(name) || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES
				|| previous != null && previous.compareTo(name) >= 0) throw new IllegalArgumentException("Invalid biome names");
			previous = name;
		}
		this.voxels = voxels.clone(); this.palettes = new byte[palettes.length][];
		boolean[] union = new boolean[this.names.size()];
		for (int section = 0; section < palettes.length; section++) {
			byte[] palette = Objects.requireNonNull(palettes[section]).clone();
			if (palette.length < 1 || palette.length > this.names.size()) throw new IllegalArgumentException("Invalid biome palette");
			boolean[] present = new boolean[this.names.size()]; int prior = -1;
			for (byte value : palette) {
				int code = Byte.toUnsignedInt(value);
				if (code <= prior || code >= present.length) throw new IllegalArgumentException("Unbound biome palette code");
				present[code] = union[code] = true; prior = code;
			}
			for (int i = section * 64; i < (section + 1) * 64; i++) {
				int code = Byte.toUnsignedInt(this.voxels[i]);
				if (code >= present.length || !present[code]) throw new IllegalArgumentException("Biome voxel outside its palette");
			}
			this.palettes[section] = palette;
		}
		for (boolean present : union) if (!present) throw new IllegalArgumentException("Unbound biome name");
	}

	public int minY() { return minY; }
	public int height() { return height; }
	public List<String> names() { return names; }
	public int code(int section, int x, int y, int z) {
		if (section < 0 || section >= palettes.length || (x | y | z) < 0 || x > 3 || y > 3 || z > 3) {
			throw new IllegalArgumentException("Biome coordinate outside center");
		}
		return Byte.toUnsignedInt(voxels[section * 64 + (x * 4 + y) * 4 + z]);
	}
	public static CompleteBiomeData capture(ChunkAccess chunk) {
		return capture(chunk.getMinY(), chunk.getHeight(), chunk.getSections());
	}
	public static CompleteBiomeData capture(int minY, int height, LevelChunkSection[] sections) {
		if (height < 16 || height > TerrainDensityJob.MAX_HEIGHT || height % 16 != 0
			|| minY % 16 != 0 || sections.length != height / 16) throw new IllegalArgumentException("Unsupported biome capture");
		TreeSet<String> union = new TreeSet<>();
		for (var section : sections) section.getBiomes().forEachInPalette(biome ->
			union.add(biome.unwrapKey().orElseThrow().identifier().toString()));
		List<String> names = List.copyOf(union);
		if (names.isEmpty() || names.size() > MAX_NAMES) throw new IllegalArgumentException("Biome capture palette bound");
		var codes = new HashMap<String, Integer>();
		for (int i = 0; i < names.size(); i++) codes.put(names.get(i), i);
		byte[][] palettes = new byte[sections.length][]; byte[] voxels = new byte[height * 4];
		for (int s = 0; s < sections.length; s++) {
			TreeSet<Integer> palette = new TreeSet<>();
			sections[s].getBiomes().forEachInPalette(b -> palette.add(codes.get(b.unwrapKey().orElseThrow().identifier().toString())));
			palettes[s] = new byte[palette.size()]; int cursor = 0;
			for (int code : palette) palettes[s][cursor++] = (byte)code;
			for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
				voxels[s * 64 + (x * 4 + y) * 4 + z] = codes.get(sections[s].getNoiseBiome(x,y,z)
					.unwrapKey().orElseThrow().identifier().toString()).byteValue();
			}
		}
		return new CompleteBiomeData(minY, height, names, palettes, voxels);
	}
	public byte[] encode() {
		int size = 14 + voxels.length;
		for (String name : names) size += 2 + name.getBytes(StandardCharsets.UTF_8).length;
		for (byte[] palette : palettes) size += 1 + palette.length;
		ByteBuffer out = ByteBuffer.allocate(size);
		out.putInt(1).putInt(minY).putInt(height).putShort((short)names.size());
		for (String name : names) {
			byte[] value = name.getBytes(StandardCharsets.UTF_8); out.putShort((short)value.length).put(value);
		}
		for (int s = 0; s < palettes.length; s++) out.put((byte)palettes[s].length).put(palettes[s]).put(voxels, s * 64, 64);
		return out.array();
	}
	public static CompleteBiomeData decode(byte[] bytes) {
		if (bytes.length < 14 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid biome byte bound");
		try {
			ByteBuffer in = ByteBuffer.wrap(bytes);
			int version = in.getInt(), minY = in.getInt(), height = in.getInt(), count = Short.toUnsignedInt(in.getShort());
			if (version != 1 || height < 16 || height > TerrainDensityJob.MAX_HEIGHT || height % 16 != 0
				|| minY % 16 != 0 || count < 1 || count > MAX_NAMES) throw new IllegalArgumentException("Invalid biome header");
			var names = new java.util.ArrayList<String>(count);
			for (int i = 0; i < count; i++) {
				int length = Short.toUnsignedInt(in.getShort());
				if (length < 1 || length > MAX_NAME_BYTES || length > in.remaining()) throw new IllegalArgumentException("Invalid biome name bound");
				byte[] name = new byte[length]; in.get(name); names.add(new String(name, StandardCharsets.UTF_8));
			}
			byte[][] palettes = new byte[height / 16][]; byte[] voxels = new byte[height * 4];
			for (int s = 0; s < palettes.length; s++) {
				int size = Byte.toUnsignedInt(in.get());
				if (size < 1 || size > count || in.remaining() < size + 64) throw new IllegalArgumentException("Invalid biome section bound");
				palettes[s] = new byte[size]; in.get(palettes[s]); in.get(voxels, s * 64, 64);
			}
			if (in.hasRemaining()) throw new IllegalArgumentException("Trailing biome bytes");
			return new CompleteBiomeData(minY, height, names, palettes, voxels);
		} catch (java.nio.BufferUnderflowException error) { throw new IllegalArgumentException("Truncated biome body", error); }
	}
	@Override public boolean equals(Object other) {
		return other instanceof CompleteBiomeData data && minY == data.minY && height == data.height
			&& names.equals(data.names) && Arrays.deepEquals(palettes, data.palettes) && Arrays.equals(voxels, data.voxels);
	}
	@Override public int hashCode() { return Objects.hash(minY, height, names, Arrays.deepHashCode(palettes), Arrays.hashCode(voxels)); }
}
