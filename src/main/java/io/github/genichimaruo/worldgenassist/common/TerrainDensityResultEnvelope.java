package io.github.genichimaruo.worldgenassist.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class TerrainDensityResultEnvelope {
	/** 26.3 samples float densities; protocol v4 transports their exact raw bits. */
	public static final int BYTES_PER_DENSITY = Float.BYTES;
	public static final int MAX_ENCODED_DENSITY_BYTES = Math.max(TerrainDensityJob.MAX_TERRAIN_SAMPLE_COUNT * BYTES_PER_DENSITY,
		CompleteTerrainData.MAX_RAW_BYTES + 4);

	private final TerrainJobIdentity identity;
	private final int densityCount;
	private final Encoding encoding;
	private final byte[] encodedDensities;
	private final long clientComputeNanos;
	private final long clientEncodeNanos;

	public TerrainDensityResultEnvelope(
		TerrainJobIdentity identity,
		int densityCount,
		Encoding encoding,
		byte[] encodedDensities,
		long clientComputeNanos,
		long clientEncodeNanos
	) {
		this.identity = Objects.requireNonNull(identity, "identity");
		if (densityCount < 1 || densityCount > TerrainDensityJob.MAX_TERRAIN_SAMPLE_COUNT) {
			throw new IllegalArgumentException(
				"Density count must be between 1 and " + TerrainDensityJob.MAX_TERRAIN_SAMPLE_COUNT + ": " + densityCount
			);
		}
		this.densityCount = densityCount;
		this.encoding = Objects.requireNonNull(encoding, "encoding");
		Objects.requireNonNull(encodedDensities, "encodedDensities");
		int rawBytes = rawBytes(densityCount, encoding);
		if (encodedDensities.length < 1 || encodedDensities.length > rawBytes) {
			throw new IllegalArgumentException(
				"Encoded density bytes must be between 1 and the raw length " + rawBytes + ": " + encodedDensities.length
			);
		}
		if (!encoding.compressed() && !encoding.completeTerrain() && encodedDensities.length != rawBytes) {
			throw new IllegalArgumentException(
				"Raw density byte count must equal " + rawBytes + ": " + encodedDensities.length
			);
		}
		this.encodedDensities = encodedDensities.clone();
		if (encoding.completeTerrain()) {
			int expanded = completeRawLength();
			if (expanded < 48 + densityCount + 1024 + densityCount / 4096 * 4 || expanded > rawBytes - 4
				|| (!encoding.compressed() && encodedDensities.length != expanded + 4)) {
				throw new IllegalArgumentException("Invalid complete terrain length");
			}
		}
		this.clientComputeNanos = requireTiming(clientComputeNanos, "Client compute time");
		this.clientEncodeNanos = requireTiming(clientEncodeNanos, "Client encode time");
	}

	public static TerrainDensityResultEnvelope encode(TerrainDensityResult result) {
		return encode(result, TerrainWorkKind.DENSITY);
	}

	public static TerrainDensityResultEnvelope encode(TerrainDensityResult result, TerrainWorkKind kind) {
		Objects.requireNonNull(result, "result");
		long startedNanos = System.nanoTime();
		if (kind == TerrainWorkKind.COMPLETE_TERRAIN) return encodeComplete(result, startedNanos);
		if (result.hasCompleteTerrain()) throw new IllegalArgumentException("Complete terrain returned for a density job");
		boolean decisions = kind == TerrainWorkKind.TERRAIN_DECISIONS_AND_SURFACE;
		Encoding rawEncoding = decisions ? Encoding.TERRAIN_CODES : Encoding.RAW;
		byte[] raw = new byte[rawBytes(result.densityCount(), rawEncoding)];
		ByteBuffer rawBuffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
		int codeCount = decisions ? decisionCount(result.densityCount()) : 0;
		boolean packed = decisions && result.hasTerrainCodes(codeCount);
		if (packed) rawBuffer.put(result.terrainCodes());
		for (int index = packed ? codeCount : 0; index < result.densityCount(); index++) {
			double value = result.densityAt(index);
			float density = (float) value;
			if (!Float.isFinite(density) || (double) density != value
				|| Double.doubleToRawLongBits((double) density) != Double.doubleToRawLongBits(value)) {
				throw new IllegalArgumentException("Density is not an exact finite float at " + index);
			}
			if (index < codeCount) {
				if (!TerrainDecisionData.validCode(value) || Float.floatToRawIntBits(density) == 0x80000000) {
					throw new IllegalArgumentException("Invalid canonical terrain decision code at " + index);
				}
				rawBuffer.put((byte)(int)value);
			} else rawBuffer.putInt(Float.floatToRawIntBits(density));
		}

		byte[] candidate = new byte[raw.length];
		int compressedLength;
		boolean finished;
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		try {
			deflater.setInput(raw);
			deflater.finish();
			compressedLength = deflater.deflate(candidate);
			finished = deflater.finished();
		} finally {
			deflater.end();
		}
		boolean compressed = finished && compressedLength < raw.length;
		Encoding encoding = compressed ? (decisions ? Encoding.DEFLATE_TERRAIN_CODES : Encoding.DEFLATE) : rawEncoding;
		byte[] encoded = compressed ? Arrays.copyOf(candidate, compressedLength) : raw;
		long encodeNanos = System.nanoTime() - startedNanos;
		return new TerrainDensityResultEnvelope(
			result.identity(),
			result.densityCount(),
			encoding,
			encoded,
			result.clientComputeNanos(),
			encodeNanos
		);
	}

	public TerrainDensityResult decode() {
		if (encoding.completeTerrain()) {
			int length = completeRawLength();
			byte[] raw = encoding.compressed() ? inflateExact(length, 4)
				: Arrays.copyOfRange(encodedDensities, 4, encodedDensities.length);
			int minY = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).getInt(4);
			CompleteTerrainData data = CompleteTerrainData.decode(raw, minY, densityCount / 256);
			return TerrainDensityResult.fromCompleteTerrain(identity, data, clientComputeNanos);
		}
		byte[] raw = switch (encoding) {
			case RAW, TERRAIN_CODES -> encodedDensities;
			case DEFLATE, DEFLATE_TERRAIN_CODES -> inflateExact();
			case COMPLETE_TERRAIN, DEFLATE_COMPLETE_TERRAIN -> throw new IllegalStateException("Complete terrain decoded separately");
		};
		ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
		int codeCount = encoding.decisions() ? decisionCount(densityCount) : 0;
		if (codeCount > 0) {
			byte[] codes = new byte[codeCount]; buffer.get(codes);
			float[] surface = new float[SurfaceDensityData.SAMPLE_COUNT];
			for (int i = 0; i < surface.length; i++) surface[i] = Float.intBitsToFloat(buffer.getInt());
			return TerrainDensityResult.fromTerrainCodes(identity, codes, surface, clientComputeNanos);
		}
		float[] densities = new float[densityCount];
		for (int index = 0; index < densities.length; index++) densities[index] = Float.intBitsToFloat(buffer.getInt());
		return TerrainDensityResult.fromFloats(identity, densities, clientComputeNanos);
	}

	private byte[] inflateExact() {
		return inflateExact(rawDensityBytes(), 0);
	}
	private byte[] inflateExact(int rawLength, int offset) {
		byte[] raw = new byte[rawLength];
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(encodedDensities, offset, encodedDensities.length - offset);
			int written = 0;
			while (!inflater.finished() && written < raw.length) {
				int count = inflater.inflate(raw, written, raw.length - written);
				if (count == 0) {
					if (inflater.needsDictionary() || inflater.needsInput()) {
						throw new IllegalArgumentException("Truncated or dictionary-dependent density stream");
					}
					throw new IllegalArgumentException("Density inflater made no progress");
				}
				written += count;
			}
			if (written == raw.length && !inflater.finished()) {
				byte[] overflow = new byte[1];
				int extra = inflater.inflate(overflow);
				if (extra != 0) {
					throw new IllegalArgumentException("Density stream expands beyond " + raw.length + " bytes");
				}
			}
			if (written != raw.length || !inflater.finished() || inflater.getRemaining() != 0) {
				throw new IllegalArgumentException(
					"Density stream does not expand to exactly " + raw.length + " bytes"
				);
			}
			return raw;
		} catch (DataFormatException error) {
			throw new IllegalArgumentException("Malformed density stream", error);
		} finally {
			inflater.end();
		}
	}

	private static long requireTiming(long nanos, String description) {
		if (nanos < 0L || nanos > TerrainDensityResult.MAX_CLIENT_COMPUTE_NANOS) {
			throw new IllegalArgumentException(
				description + " must be between 0 and " + TerrainDensityResult.MAX_CLIENT_COMPUTE_NANOS + " nanoseconds: " + nanos
			);
		}
		return nanos;
	}

	public TerrainJobIdentity identity() {
		return identity;
	}

	public int densityCount() {
		return densityCount;
	}

	public Encoding encoding() {
		return encoding;
	}

	public byte[] encodedDensities() {
		return encodedDensities.clone();
	}

	public int encodedDensityBytes() {
		return encodedDensities.length;
	}

	public int rawDensityBytes() {
		if (encoding.completeTerrain()) return completeRawLength() + 4;
		return rawBytes(densityCount, encoding);
	}

	public static int rawBytes(int count, Encoding encoding) {
		if (encoding.completeTerrain()) {
			if (count < 4096 || count > TerrainDensityJob.MAX_SAMPLE_COUNT || count % 4096 != 0) {
				throw new IllegalArgumentException("Complete terrain requires section-aligned volume");
			}
			return 4 + 48 + count + 1024 + count / 4096 * 4 + count * CompleteTerrainData.MAX_POST_PROCESS_PER_BLOCK * 2
				+ 5 + CompleteBiomeData.MAX_BYTES;
		}
		return encoding.decisions() ? Math.addExact(decisionCount(count), SurfaceDensityData.SAMPLE_COUNT * Float.BYTES)
			: Math.multiplyExact(count, BYTES_PER_DENSITY);
	}
	private static int decisionCount(int count) {
		int prefix = count - SurfaceDensityData.SAMPLE_COUNT;
		if (prefix < 256 || prefix > TerrainDensityJob.MAX_SAMPLE_COUNT || prefix % (256 * 8) != 0) {
			throw new IllegalArgumentException("Packed terrain decisions require aligned full-block geometry");
		}
		return prefix;
	}
	private int completeRawLength() {
		if (encodedDensities.length < 5) throw new IllegalArgumentException("Truncated complete terrain header");
		return ByteBuffer.wrap(encodedDensities).order(ByteOrder.BIG_ENDIAN).getInt();
	}
	private static TerrainDensityResultEnvelope encodeComplete(TerrainDensityResult result, long started) {
		byte[] raw = result.completeTerrain().encode();
		byte[] compressed = new byte[raw.length];
		int count; boolean finished;
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		try {
			deflater.setInput(raw); deflater.finish(); count = deflater.deflate(compressed); finished = deflater.finished();
		} finally { deflater.end(); }
		boolean useCompression = finished && count < raw.length;
		int size = useCompression ? count : raw.length;
		byte[] encoded = ByteBuffer.allocate(size + 4).order(ByteOrder.BIG_ENDIAN).putInt(raw.length)
			.put(useCompression ? compressed : raw, 0, size).array();
		return new TerrainDensityResultEnvelope(result.identity(), result.densityCount(),
			useCompression ? Encoding.DEFLATE_COMPLETE_TERRAIN : Encoding.COMPLETE_TERRAIN,
			encoded, result.clientComputeNanos(), System.nanoTime() - started);
	}

	public long clientComputeNanos() {
		return clientComputeNanos;
	}

	public long clientEncodeNanos() {
		return clientEncodeNanos;
	}

	@Override
	public boolean equals(Object other) {
		return this == other
			|| other instanceof TerrainDensityResultEnvelope envelope
				&& identity.equals(envelope.identity)
				&& densityCount == envelope.densityCount
				&& encoding == envelope.encoding
				&& Arrays.equals(encodedDensities, envelope.encodedDensities)
				&& clientComputeNanos == envelope.clientComputeNanos
				&& clientEncodeNanos == envelope.clientEncodeNanos;
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(identity, densityCount, encoding, clientComputeNanos, clientEncodeNanos);
		return 31 * result + Arrays.hashCode(encodedDensities);
	}

	public enum Encoding {
		RAW,
		DEFLATE,
		TERRAIN_CODES,
		DEFLATE_TERRAIN_CODES,
		COMPLETE_TERRAIN,
		DEFLATE_COMPLETE_TERRAIN;
		public boolean compressed() { return this == DEFLATE || this == DEFLATE_TERRAIN_CODES || this == DEFLATE_COMPLETE_TERRAIN; }
		public boolean decisions() { return this == TERRAIN_CODES || this == DEFLATE_TERRAIN_CODES; }
		public boolean completeTerrain() { return this == COMPLETE_TERRAIN || this == DEFLATE_COMPLETE_TERRAIN; }
	}
}
