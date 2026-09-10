package io.github.genichimaruo.worldgenassist.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Bounded lossless wire envelope for an unregistered seeded-leaf result draft. */
public final class SeededLeafDensityResultEnvelope {
	public static final int FORMAT_VERSION = 1;
	public static final int BYTES_PER_DENSITY = Double.BYTES;
	public static final int MAX_ENCODED_DENSITY_BYTES = TerrainDensityJob.MAX_SAMPLE_COUNT * BYTES_PER_DENSITY;

	private final SeededLeafJobClaim claim;
	private final int densityCount;
	private final Encoding encoding;
	private final byte[] encodedDensities;
	private final long clientComputeNanos;
	private final long clientEncodeNanos;

	public SeededLeafDensityResultEnvelope(
		SeededLeafJobClaim claim,
		int densityCount,
		Encoding encoding,
		byte[] encodedDensities,
		long clientComputeNanos,
		long clientEncodeNanos
	) {
		this.claim = Objects.requireNonNull(claim, "claim");
		if (densityCount < 1 || densityCount > TerrainDensityJob.MAX_SAMPLE_COUNT) {
			throw new IllegalArgumentException(
				"Density count must be between 1 and " + TerrainDensityJob.MAX_SAMPLE_COUNT + ": " + densityCount
			);
		}
		this.densityCount = densityCount;
		this.encoding = Objects.requireNonNull(encoding, "encoding");
		Objects.requireNonNull(encodedDensities, "encodedDensities");
		int rawBytes = Math.multiplyExact(densityCount, BYTES_PER_DENSITY);
		if (encodedDensities.length < 1 || encodedDensities.length > rawBytes) {
			throw new IllegalArgumentException(
				"Encoded density bytes must be between 1 and the raw length " + rawBytes + ": " + encodedDensities.length
			);
		}
		if (encoding == Encoding.RAW && encodedDensities.length != rawBytes) {
			throw new IllegalArgumentException(
				"Raw density byte count must equal " + rawBytes + ": " + encodedDensities.length
			);
		}
		this.encodedDensities = encodedDensities.clone();
		this.clientComputeNanos = requireTiming(clientComputeNanos, "Client compute time");
		this.clientEncodeNanos = requireTiming(clientEncodeNanos, "Client encode time");
	}

	public static SeededLeafDensityResultEnvelope encode(SeededLeafDensityResult result) {
		Objects.requireNonNull(result, "result");
		long startedNanos = System.nanoTime();
		byte[] raw = new byte[Math.multiplyExact(result.densityCount(), BYTES_PER_DENSITY)];
		ByteBuffer rawBuffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
		for (int index = 0; index < result.densityCount(); index++) {
			rawBuffer.putDouble(result.densityAt(index));
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
		Encoding encoding = finished && compressedLength < raw.length ? Encoding.DEFLATE : Encoding.RAW;
		byte[] encoded = encoding == Encoding.DEFLATE ? Arrays.copyOf(candidate, compressedLength) : raw;
		return new SeededLeafDensityResultEnvelope(
			result.claim(),
			result.densityCount(),
			encoding,
			encoded,
			result.clientComputeNanos(),
			System.nanoTime() - startedNanos
		);
	}

	public SeededLeafDensityResult decode() {
		byte[] raw = switch (encoding) {
			case RAW -> encodedDensities.clone();
			case DEFLATE -> inflateExact();
		};
		ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
		double[] densities = new double[densityCount];
		for (int index = 0; index < densities.length; index++) {
			densities[index] = buffer.getDouble();
		}
		return new SeededLeafDensityResult(claim, densities, clientComputeNanos);
	}

	private byte[] inflateExact() {
		int rawLength = Math.multiplyExact(densityCount, BYTES_PER_DENSITY);
		byte[] raw = new byte[rawLength];
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(encodedDensities);
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
				throw new IllegalArgumentException("Density stream does not expand to exactly " + raw.length + " bytes");
			}
			return raw;
		} catch (DataFormatException error) {
			throw new IllegalArgumentException("Malformed density stream", error);
		} finally {
			inflater.end();
		}
	}

	private static long requireTiming(long nanos, String description) {
		if (nanos < 0L || nanos > SeededLeafDensityResult.MAX_CLIENT_COMPUTE_NANOS) {
			throw new IllegalArgumentException(
				description + " must be between 0 and " + SeededLeafDensityResult.MAX_CLIENT_COMPUTE_NANOS
					+ " nanoseconds: " + nanos
			);
		}
		return nanos;
	}

	public SeededLeafJobClaim claim() {
		return claim;
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
		return densityCount * BYTES_PER_DENSITY;
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
			|| other instanceof SeededLeafDensityResultEnvelope envelope
				&& claim.equals(envelope.claim)
				&& densityCount == envelope.densityCount
				&& encoding == envelope.encoding
				&& Arrays.equals(encodedDensities, envelope.encodedDensities)
				&& clientComputeNanos == envelope.clientComputeNanos
				&& clientEncodeNanos == envelope.clientEncodeNanos;
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(claim, densityCount, encoding, clientComputeNanos, clientEncodeNanos);
		return 31 * result + Arrays.hashCode(encodedDensities);
	}

	public enum Encoding {
		RAW,
		DEFLATE
	}
}
