package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeededLeafPersistentDisclosureLedgerTest {
	private static final String LEDGER_NAME = "seeded-leaf-disclosure-ledger.bin";
	private static final byte[] MARKER = "CAWG seeded-leaf disclosure ledger initialized v1\n"
		.getBytes(StandardCharsets.US_ASCII);

	@TempDir
	Path temporaryDirectory;

	@Test
	void createsFixedRecordMarkerAndPersistsCommittedCountersAcrossReopen() throws Exception {
		Path ledgerPath = ledgerPath();
		SeededLeafPersistentDisclosureLedger ledger = new SeededLeafPersistentDisclosureLedger(5);
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.OPENED, ledger.open(ledgerPath));
		assertEquals(SeededLeafPersistentDisclosureLedger.ENCODED_BYTES, Files.size(ledgerPath));
		assertArrayEquals(MARKER, Files.readAllBytes(markerPath(ledgerPath)));
		assertSnapshot(ledger, SeededLeafGlobalDisclosureBudget.State.ACTIVE, 0L, 0L);

		assertEquals(SeededLeafGlobalDisclosureBudget.ChargeStatus.ACCEPTED, ledger.tryCharge(2));
		assertSnapshot(ledger, SeededLeafGlobalDisclosureBudget.State.ACTIVE, 2L, 1L);
		ledger.close();

		SeededLeafPersistentDisclosureLedger reopened = new SeededLeafPersistentDisclosureLedger(5);
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.OPENED, reopened.open(ledgerPath));
		assertSnapshot(reopened, SeededLeafGlobalDisclosureBudget.State.ACTIVE, 2L, 1L);
	}

	@Test
	void exhaustionDoesNotAlterBytesOrCounters() throws Exception {
		SeededLeafPersistentDisclosureLedger ledger = open(2);
		assertEquals(SeededLeafGlobalDisclosureBudget.ChargeStatus.ACCEPTED, ledger.tryCharge(2));
		byte[] before = Files.readAllBytes(ledgerPath());

		assertEquals(SeededLeafGlobalDisclosureBudget.ChargeStatus.EXHAUSTED, ledger.tryCharge(1));
		assertArrayEquals(before, Files.readAllBytes(ledgerPath()));
		assertSnapshot(ledger, SeededLeafGlobalDisclosureBudget.State.ACTIVE, 2L, 1L);
	}

	@Test
	void concurrentChargesHaveExactWinnersAndCounters() throws Exception {
		SeededLeafPersistentDisclosureLedger ledger = open(8);
		List<Callable<SeededLeafGlobalDisclosureBudget.ChargeStatus>> charges = new ArrayList<>();
		for (int index = 0; index < 16; index++) {
			charges.add(() -> ledger.tryCharge(1));
		}
		List<SeededLeafGlobalDisclosureBudget.ChargeStatus> results;
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			results = executor.invokeAll(charges).stream().map(future -> {
				try {
					return future.get();
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
			}).toList();
		}
		assertEquals(8, results.stream().filter(status -> status == SeededLeafGlobalDisclosureBudget.ChargeStatus.ACCEPTED).count());
		assertEquals(8, results.stream().filter(status -> status == SeededLeafGlobalDisclosureBudget.ChargeStatus.EXHAUSTED).count());
		assertSnapshot(ledger, SeededLeafGlobalDisclosureBudget.State.ACTIVE, 8L, 8L);
	}

	@Test
	void truncatedAndAmbiguousOrCorruptFilesFailClosedWithoutDeletion() throws Exception {
		Path ledgerPath = ledgerPath();
		Files.createDirectories(ledgerPath.getParent());
		for (int length = 0; length < SeededLeafPersistentDisclosureLedger.ENCODED_BYTES; length++) {
			Files.write(ledgerPath, new byte[length]);
			SeededLeafPersistentDisclosureLedger ledger = new SeededLeafPersistentDisclosureLedger(4);
			assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.FAILED, ledger.open(ledgerPath), "length=" + length);
			assertEquals(SeededLeafGlobalDisclosureBudget.State.FAILED, ledger.snapshot().state());
		}

		Files.delete(ledgerPath);
		Files.write(pendingPath(ledgerPath), new byte[] {1});
		SeededLeafPersistentDisclosureLedger pending = new SeededLeafPersistentDisclosureLedger(4);
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.FAILED, pending.open(ledgerPath));
		assertTrue(Files.exists(pendingPath(ledgerPath)));

		Files.delete(pendingPath(ledgerPath));
		SeededLeafPersistentDisclosureLedger initialized = open(4);
		initialized.close();
		byte[] valid = Files.readAllBytes(ledgerPath);
		for (int offset : new int[] {0, 12, 16, 24, 32, 40, 71}) {
			byte[] mutated = valid.clone();
			mutated[offset] ^= 1;
			Files.write(ledgerPath, mutated);
			SeededLeafPersistentDisclosureLedger corrupt = new SeededLeafPersistentDisclosureLedger(4);
			assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.FAILED, corrupt.open(ledgerPath), "offset=" + offset);
		}
	}

	@Test
	void validChecksumWithDifferentMaximumAndMissingOrMalformedMarkerFailClosed() throws Exception {
		SeededLeafPersistentDisclosureLedger ledger = open(4);
		ledger.close();
		Path ledgerPath = ledgerPath();
		Files.write(ledgerPath, encoded(5L, 0L, 0L));
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.FAILED,
			new SeededLeafPersistentDisclosureLedger(4).open(ledgerPath));

		Files.delete(ledgerPath);
		SeededLeafPersistentDisclosureLedger missing = new SeededLeafPersistentDisclosureLedger(4);
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.FAILED, missing.open(ledgerPath));
		assertTrue(Files.exists(markerPath(ledgerPath)));

		Files.write(markerPath(ledgerPath), new byte[] {0});
		Files.write(ledgerPath, encoded(4L, 0L, 0L));
		SeededLeafPersistentDisclosureLedger malformedMarker = new SeededLeafPersistentDisclosureLedger(4);
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.FAILED, malformedMarker.open(ledgerPath));
	}

	@Test
	void failedInstanceCanReopenAfterDiskIsRepaired() throws Exception {
		Path ledgerPath = ledgerPath();
		Files.createDirectories(ledgerPath.getParent());
		Files.write(ledgerPath, new byte[] {1});
		SeededLeafPersistentDisclosureLedger ledger = new SeededLeafPersistentDisclosureLedger(3);
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.FAILED, ledger.open(ledgerPath));
		Files.write(ledgerPath, encoded(3L, 1L, 1L));
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.OPENED, ledger.open(ledgerPath));
		assertSnapshot(ledger, SeededLeafGlobalDisclosureBudget.State.ACTIVE, 1L, 1L);
	}

	@Test
	void encodedLedgerAndMarkerContainCountersOnly() throws Exception {
		SeededLeafPersistentDisclosureLedger ledger = open(4);
		assertEquals(SeededLeafGlobalDisclosureBudget.ChargeStatus.ACCEPTED, ledger.tryCharge(1));
		byte[] encoded = Files.readAllBytes(ledgerPath());
		assertFalse(contains(encoded, "8675309".getBytes(StandardCharsets.US_ASCII)));
		assertFalse(contains(encoded, "transcript".getBytes(StandardCharsets.US_ASCII)));
		assertFalse(contains(encoded, "authentication".getBytes(StandardCharsets.US_ASCII)));
		assertArrayEquals(MARKER, Files.readAllBytes(markerPath(ledgerPath())));
	}

	private SeededLeafPersistentDisclosureLedger open(long maximum) {
		SeededLeafPersistentDisclosureLedger ledger = new SeededLeafPersistentDisclosureLedger(maximum);
		assertEquals(SeededLeafPersistentDisclosureLedger.OpenStatus.OPENED, ledger.open(ledgerPath()));
		return ledger;
	}

	private Path ledgerPath() {
		return temporaryDirectory.resolve(LEDGER_NAME);
	}

	private static Path markerPath(Path ledgerPath) {
		return ledgerPath.resolveSibling(ledgerPath.getFileName() + ".initialized");
	}

	private static Path pendingPath(Path ledgerPath) {
		return ledgerPath.resolveSibling(ledgerPath.getFileName() + ".pending");
	}

	private static void assertSnapshot(
		SeededLeafPersistentDisclosureLedger ledger,
		SeededLeafGlobalDisclosureBudget.State expectedState,
		long used,
		long sequence
	) {
		SeededLeafGlobalDisclosureBudget.Snapshot snapshot = ledger.snapshot();
		assertEquals(expectedState, snapshot.state());
		assertEquals(used, snapshot.usedEntries());
		assertEquals(sequence, snapshot.sequence());
	}

	private static byte[] encoded(long maximum, long used, long sequence) throws Exception {
		ByteBuffer buffer = ByteBuffer.allocate(SeededLeafPersistentDisclosureLedger.ENCODED_BYTES).order(ByteOrder.BIG_ENDIAN);
		buffer.putLong(0x43415747534c4431L);
		buffer.putInt(SeededLeafPersistentDisclosureLedger.FORMAT_VERSION);
		buffer.putInt(0);
		buffer.putLong(maximum);
		buffer.putLong(used);
		buffer.putLong(sequence);
		byte[] bytes = buffer.array();
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update("worldgen_assist/seeded_leaf_disclosure_ledger/v1\0".getBytes(StandardCharsets.UTF_8));
		digest.update(bytes, 0, 40);
		System.arraycopy(digest.digest(), 0, bytes, 40, 32);
		return bytes;
	}

	private static boolean contains(byte[] bytes, byte[] needle) {
		for (int index = 0; index <= bytes.length - needle.length; index++) {
			if (Arrays.equals(needle, Arrays.copyOfRange(bytes, index, index + needle.length))) {
				return true;
			}
		}
		return false;
	}
}
