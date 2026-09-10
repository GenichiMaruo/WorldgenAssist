package io.github.genichimaruo.worldgenassist.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;

/**
 * Fixed-format world-local disclosure ledger. It stores counters only: never a
 * seed, transcript, context ID, authentication key, or authentication tag.
 */
public final class SeededLeafPersistentDisclosureLedger implements SeededLeafGlobalDisclosureBudget {
	public static final int FORMAT_VERSION = 1;
	public static final int ENCODED_BYTES = 72;
	private static final long MAGIC = 0x43415747534c4431L; // CAWGSLD1
	private static final int CHECKSUM_BYTES = 32;
	private static final int CHECKSUM_OFFSET = ENCODED_BYTES - CHECKSUM_BYTES;
	private static final String PENDING_SUFFIX = ".pending";
	private static final String INITIALIZED_SUFFIX = ".initialized";
	private static final byte[] INITIALIZED_MARKER = "CAWG seeded-leaf disclosure ledger initialized v1\n"
		.getBytes(StandardCharsets.US_ASCII);

	private final long maximumEntries;
	private State state = State.CLOSED;
	private Path path;
	private long usedEntries;
	private long sequence;
	private String failureReason = "";

	public SeededLeafPersistentDisclosureLedger(long maximumEntries) {
		if (maximumEntries < 1L) {
			throw new IllegalArgumentException("maximumEntries must be positive: " + maximumEntries);
		}
		this.maximumEntries = maximumEntries;
	}

	/** Opens or creates the ledger. Any ambiguous or malformed state fails closed. */
	public synchronized OpenStatus open(Path ledgerPath) {
		Objects.requireNonNull(ledgerPath, "ledgerPath");
		if (state == State.ACTIVE) {
			throw new IllegalStateException("Seeded-leaf disclosure ledger is already open");
		}
		usedEntries = 0L;
		sequence = 0L;
		failureReason = "";
		state = State.CLOSED;
		path = ledgerPath.toAbsolutePath().normalize();
		try {
			Path parent = Objects.requireNonNull(path.getParent(), "Ledger path must have a parent directory");
			Files.createDirectories(parent);
			Path pending = pendingPath(path);
			Path initialized = initializedPath(path);
			if (Files.exists(pending)) {
				return fail("pending_file_present");
			}
			if (Files.exists(path)) {
				if (Files.size(path) != ENCODED_BYTES) {
					return fail("invalid_format");
				}
				Decoded decoded = decode(Files.readAllBytes(path), maximumEntries);
				usedEntries = decoded.usedEntries;
				sequence = decoded.sequence;
				if (Files.exists(initialized)) {
					validateInitializedMarker(initialized);
				} else {
					persistInitializedMarker(initialized);
				}
			} else {
				if (Files.exists(initialized)) {
					return fail("ledger_missing_after_initialization");
				}
				persistInitializedMarker(initialized);
				persist(path, maximumEntries, 0L, 0L);
			}
			state = State.ACTIVE;
			return OpenStatus.OPENED;
		} catch (IOException | RuntimeException exception) {
			return fail(reason(exception));
		}
	}

	@Override
	public synchronized ChargeStatus tryCharge(int transcriptEntries) {
		if (transcriptEntries < 1 || transcriptEntries > SeededLeafTranscript.MAX_ENTRIES) {
			throw new IllegalArgumentException("Invalid transcript entry charge: " + transcriptEntries);
		}
		if (state != State.ACTIVE) {
			return ChargeStatus.UNAVAILABLE;
		}
		if (transcriptEntries > maximumEntries - usedEntries) {
			return ChargeStatus.EXHAUSTED;
		}
		long nextUsed = Math.addExact(usedEntries, transcriptEntries);
		long nextSequence = Math.incrementExact(sequence);
		try {
			persist(path, maximumEntries, nextUsed, nextSequence);
			usedEntries = nextUsed;
			sequence = nextSequence;
			return ChargeStatus.ACCEPTED;
		} catch (IOException | RuntimeException exception) {
			fail(reason(exception));
			return ChargeStatus.UNAVAILABLE;
		}
	}

	public synchronized void close() {
		if (state == State.ACTIVE) {
			state = State.CLOSED;
		}
	}

	@Override
	public synchronized Snapshot snapshot() {
		return new Snapshot(state, maximumEntries, usedEntries, sequence, failureReason);
	}

	private OpenStatus fail(String reason) {
		state = State.FAILED;
		failureReason = reason;
		return OpenStatus.FAILED;
	}

	private static void persist(Path path, long maximumEntries, long usedEntries, long sequence) throws IOException {
		byte[] encoded = encode(maximumEntries, usedEntries, sequence);
		Path pending = pendingPath(path);
		if (Files.exists(pending)) {
			throw new IOException("Pending disclosure ledger already exists");
		}
		Files.write(
			pending,
			encoded,
			StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE,
			StandardOpenOption.SYNC
		);
		try {
			Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.deleteIfExists(pending);
			throw new IOException("Atomic disclosure-ledger replacement is not supported", exception);
		}
	}

	private static Path pendingPath(Path path) {
		return path.resolveSibling(path.getFileName() + PENDING_SUFFIX);
	}

	private static Path initializedPath(Path path) {
		return path.resolveSibling(path.getFileName() + INITIALIZED_SUFFIX);
	}

	private static void persistInitializedMarker(Path marker) throws IOException {
		Files.write(
			marker,
			INITIALIZED_MARKER,
			StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE,
			StandardOpenOption.SYNC
		);
	}

	private static void validateInitializedMarker(Path marker) throws IOException {
		if (!Arrays.equals(Files.readAllBytes(marker), INITIALIZED_MARKER)) {
			throw new IllegalArgumentException("Invalid disclosure-ledger initialization marker");
		}
	}

	private static byte[] encode(long maximumEntries, long usedEntries, long sequence) {
		ByteBuffer buffer = ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.BIG_ENDIAN);
		buffer.putLong(MAGIC);
		buffer.putInt(FORMAT_VERSION);
		buffer.putInt(0);
		buffer.putLong(maximumEntries);
		buffer.putLong(usedEntries);
		buffer.putLong(sequence);
		byte[] bytes = buffer.array();
		byte[] checksum = checksum(bytes, 0, CHECKSUM_OFFSET);
		System.arraycopy(checksum, 0, bytes, CHECKSUM_OFFSET, CHECKSUM_BYTES);
		return bytes;
	}

	private static Decoded decode(byte[] bytes, long configuredMaximum) {
		if (bytes.length != ENCODED_BYTES) {
			throw new IllegalArgumentException("Disclosure ledger must contain exactly " + ENCODED_BYTES + " bytes");
		}
		byte[] expectedChecksum = checksum(bytes, 0, CHECKSUM_OFFSET);
		byte[] actualChecksum = Arrays.copyOfRange(bytes, CHECKSUM_OFFSET, ENCODED_BYTES);
		if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
			throw new IllegalArgumentException("Disclosure ledger checksum does not match");
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		long magic = buffer.getLong();
		int version = buffer.getInt();
		int reserved = buffer.getInt();
		long storedMaximum = buffer.getLong();
		long usedEntries = buffer.getLong();
		long sequence = buffer.getLong();
		if (magic != MAGIC || version != FORMAT_VERSION || reserved != 0) {
			throw new IllegalArgumentException("Unsupported disclosure ledger header");
		}
		if (storedMaximum != configuredMaximum) {
			throw new IllegalArgumentException("Configured disclosure maximum differs from persisted ledger");
		}
		if (usedEntries < 0L || usedEntries > storedMaximum || sequence < 0L) {
			throw new IllegalArgumentException("Invalid disclosure ledger counters");
		}
		return new Decoded(usedEntries, sequence);
	}

	private static byte[] checksum(byte[] bytes, int offset, int length) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update("worldgen_assist/seeded_leaf_disclosure_ledger/v1\0".getBytes(StandardCharsets.UTF_8));
			digest.update(bytes, offset, length);
			return digest.digest();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String reason(Exception exception) {
		if (exception instanceof IllegalArgumentException) {
			return "invalid_format";
		}
		if (exception instanceof ArithmeticException) {
			return "counter_overflow";
		}
		return "io_failure";
	}

	public enum OpenStatus {
		OPENED,
		FAILED
	}

	private record Decoded(long usedEntries, long sequence) {
	}
}
