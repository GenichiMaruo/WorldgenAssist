package io.github.genichimaruo.worldgenassist.server;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.SeededLeafTranscript;

/**
 * Bounded, thread-confined recorder/replayer for seed-dependent density leaves.
 *
 * <p>The trace intentionally contains no world seed, random source, initialized
 * sampler, offset, or permutation table. It is a local research boundary and
 * is not yet a network protocol.</p>
 */
public final class SeededLeafTrace {
	public static final int MAX_ENTRIES = SeededLeafTranscript.MAX_ENTRIES;

	private static final ThreadLocal<SessionState> ACTIVE = new ThreadLocal<>();
	/** Test-only, thread-confined observer of completed trace-entry counts. */
	private static final ThreadLocal<TestEntryObserver> TEST_ENTRY_OBSERVER = new ThreadLocal<>();
	private static final AtomicInteger ACTIVE_SESSION_COUNT = new AtomicInteger();

	private SeededLeafTrace() {
	}

	public static Recording beginRecording(NoiseRouter router, int maxEntries) {
		validateMaxEntries(maxEntries);
		ensureInactive();
		RecordingState state = new RecordingState(LeafRegistry.create(router), maxEntries);
		ACTIVE.set(state);
		ACTIVE_SESSION_COUNT.incrementAndGet();
		WorldgenAssist.LOGGER.info("[CAWG] seed_trace.record_started max_entries={}", maxEntries);
		return new Recording(state, Thread.currentThread());
	}

	public static Replay beginReplay(NoiseRouter router, SeededLeafTranscript transcript) {
		Objects.requireNonNull(transcript, "transcript");
		ensureInactive();
		ReplayState state = new ReplayState(LeafRegistry.create(router), transcript);
		ACTIVE.set(state);
		ACTIVE_SESSION_COUNT.incrementAndGet();
		WorldgenAssist.LOGGER.info("[CAWG] seed_trace.replay_started expected_entries={}", transcript.size());
		return new Replay(state, Thread.currentThread());
	}

	public static Double replayNormalNoise(NormalNoise noise, double x, double y, double z) {
		SessionState state = ACTIVE.get();
		return state instanceof ReplayState replay
			? replay.next(SeededLeafTranscript.Kind.NORMAL_NOISE, noise, bits(x), bits(y), bits(z))
			: null;
	}

	public static boolean hasActiveSession() {
		return ACTIVE_SESSION_COUNT.get() != 0;
	}

	/**
	 * Opens a thread-confined test observer that receives only completed entry counts.
	 * This is not public API; production callers leave the observer unset.
	 */
	static TestEntryObserverScope beginTestEntryObserver(IntConsumer observer) {
		Objects.requireNonNull(observer, "observer");
		if (TEST_ENTRY_OBSERVER.get() != null) {
			throw new IllegalStateException("A seeded-leaf test entry observer is already active on this thread");
		}
		TestEntryObserver state = new TestEntryObserver(observer);
		TEST_ENTRY_OBSERVER.set(state);
		return new TestEntryObserverScope(state, Thread.currentThread());
	}

	public static void recordNormalNoise(NormalNoise noise, double x, double y, double z, double value) {
		SessionState state = ACTIVE.get();
		if (state instanceof RecordingState recording) {
			recording.add(SeededLeafTranscript.Kind.NORMAL_NOISE, noise, bits(x), bits(y), bits(z), bits(value));
		}
	}

	public static Double replayBlendedNoise(BlendedNoise noise, DensityFunction.FunctionContext context) {
		SessionState state = ACTIVE.get();
		return state instanceof ReplayState replay
			? replay.next(SeededLeafTranscript.Kind.BLENDED_NOISE, noise, context.blockX(), context.blockY(), context.blockZ())
			: null;
	}

	public static void recordBlendedNoise(
		BlendedNoise noise,
		DensityFunction.FunctionContext context,
		double value
	) {
		SessionState state = ACTIVE.get();
		if (state instanceof RecordingState recording) {
			recording.add(
				SeededLeafTranscript.Kind.BLENDED_NOISE,
				noise,
				context.blockX(),
				context.blockY(),
				context.blockZ(),
				bits(value)
			);
		}
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private static void observeCompletedEntry(int count) {
		TestEntryObserver observer = TEST_ENTRY_OBSERVER.get();
		if (observer != null) {
			observer.accept(count);
		}
	}

	private static void validateMaxEntries(int maxEntries) {
		if (maxEntries < 1 || maxEntries > MAX_ENTRIES) {
			throw new IllegalArgumentException("maxEntries must be between 1 and " + MAX_ENTRIES + ": " + maxEntries);
		}
	}

	private static void ensureInactive() {
		if (ACTIVE.get() != null) {
			throw new IllegalStateException("A seeded-leaf trace session is already active on this thread");
		}
	}

	private static void close(SessionState expected, Thread owner) {
		if (Thread.currentThread() != owner) {
			throw new IllegalStateException("A seeded-leaf trace session must close on its owning thread");
		}
		if (ACTIVE.get() != expected) {
			throw new IllegalStateException("The seeded-leaf trace session is not active");
		}
		ACTIVE.remove();
		ACTIVE_SESSION_COUNT.decrementAndGet();
	}

	public static final class Recording implements AutoCloseable {
		private final RecordingState state;
		private final Thread owner;
		private boolean closed;

		private Recording(RecordingState state, Thread owner) {
			this.state = state;
			this.owner = owner;
		}

		@Override
		public void close() {
			if (!closed) {
				SeededLeafTrace.close(state, owner);
				closed = true;
				WorldgenAssist.LOGGER.info("[CAWG] seed_trace.record_closed entries={}", state.entries.size());
			}
		}

		public SeededLeafTranscript transcript() {
			if (!closed) {
				throw new IllegalStateException("Close the recording before reading its transcript");
			}
			return new SeededLeafTranscript(state.entries);
		}
	}

	public static final class Replay implements AutoCloseable {
		private final ReplayState state;
		private final Thread owner;
		private boolean closed;

		private Replay(ReplayState state, Thread owner) {
			this.state = state;
			this.owner = owner;
		}

		@Override
		public void close() {
			if (!closed) {
				SeededLeafTrace.close(state, owner);
				closed = true;
				state.requireExhausted();
				WorldgenAssist.LOGGER.info("[CAWG] seed_trace.replay_complete entries={}", state.cursor);
			}
		}

		public int consumedEntries() {
			return state.cursor;
		}
	}

	/** Package-private test scope: it reveals counts, never leaves, coordinates, or values. */
	static final class TestEntryObserverScope implements AutoCloseable {
		private TestEntryObserver observer;
		private final Thread owner;
		private boolean closed;

		private TestEntryObserverScope(TestEntryObserver observer, Thread owner) {
			this.observer = observer;
			this.owner = owner;
		}

		@Override
		public void close() {
			if (Thread.currentThread() != owner) {
				throw new IllegalStateException("A seeded-leaf test entry observer must close on its owning thread");
			}
			if (!closed) {
				TestEntryObserver activeObserver = observer;
				if (TEST_ENTRY_OBSERVER.get() != activeObserver) {
					throw new IllegalStateException("The seeded-leaf test entry observer is not active");
				}
				TEST_ENTRY_OBSERVER.remove();
				observer = null;
				closed = true;
			}
		}
	}

	public static final class TraceMismatchException extends IllegalStateException {
		private TraceMismatchException(String message) {
			super(message);
		}
	}

	private sealed interface SessionState permits RecordingState, ReplayState {
	}

	private static final class RecordingState implements SessionState {
		private final LeafRegistry registry;
		private final int maxEntries;
		private final List<SeededLeafTranscript.Entry> entries = new ArrayList<>();

		private RecordingState(LeafRegistry registry, int maxEntries) {
			this.registry = registry;
			this.maxEntries = maxEntries;
		}

		private void add(SeededLeafTranscript.Kind kind, Object leaf, long xBits, long yBits, long zBits, long valueBits) {
			if (entries.size() == maxEntries) {
				throw new TraceMismatchException("Seeded-leaf recording exceeded its bound of " + maxEntries + " entries");
			}
			entries.add(new SeededLeafTranscript.Entry(kind, registry.requireId(kind, leaf), xBits, yBits, zBits, valueBits));
			observeCompletedEntry(entries.size());
		}
	}

	private static final class ReplayState implements SessionState {
		private final LeafRegistry registry;
		private final SeededLeafTranscript transcript;
		private int cursor;

		private ReplayState(LeafRegistry registry, SeededLeafTranscript transcript) {
			this.registry = registry;
			this.transcript = transcript;
		}

		private double next(SeededLeafTranscript.Kind kind, Object leaf, long xBits, long yBits, long zBits) {
			if (cursor >= transcript.entries().size()) {
				throw new TraceMismatchException("Seeded-leaf replay requested entry " + cursor + " after trace exhaustion");
			}
			SeededLeafTranscript.Entry expected = transcript.entries().get(cursor);
			String leafId = registry.requireId(kind, leaf);
			if (expected.kind() != kind
				|| !expected.leafId().equals(leafId)
				|| expected.xBits() != xBits
				|| expected.yBits() != yBits
				|| expected.zBits() != zBits) {
				throw new TraceMismatchException(
					"Seeded-leaf replay mismatch at entry " + cursor
						+ ": expected=" + expected.kind() + "/" + expected.leafId()
						+ " actual=" + kind + "/" + leafId
				);
			}
			cursor++;
			observeCompletedEntry(cursor);
			return Double.longBitsToDouble(expected.valueBits());
		}

		private void requireExhausted() {
			if (cursor != transcript.entries().size()) {
				throw new TraceMismatchException(
					"Seeded-leaf replay consumed " + cursor + " of " + transcript.entries().size() + " entries"
				);
			}
		}
	}

	private static final class TestEntryObserver {
		private final IntConsumer observer;

		private TestEntryObserver(IntConsumer observer) {
			this.observer = observer;
		}

		private void accept(int count) {
			observer.accept(count);
		}
	}

	private static final class LeafRegistry {
		private final Map<Object, String> normalNoises = new IdentityHashMap<>();
		private final Map<Object, String> blendedNoises = new IdentityHashMap<>();

		private static LeafRegistry create(NoiseRouter router) {
			Objects.requireNonNull(router, "router");
			LeafRegistry registry = new LeafRegistry();
			DensityFunction.Visitor visitor = new DensityFunction.Visitor() {
				@Override
				public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
					NormalNoise noise = holder.noise();
					if (noise == null) {
						throw new IllegalArgumentException("Cannot trace an unwired noise holder");
					}
					String id = holder.noiseData().unwrapKey()
						.map(key -> "normal:" + key.identifier())
						.orElseThrow(() -> new IllegalArgumentException("Cannot trace an unkeyed noise holder"));
					String previous = registry.normalNoises.putIfAbsent(noise, id);
					if (previous != null && !previous.equals(id)) {
						throw new IllegalArgumentException("One NormalNoise instance is bound to multiple IDs");
					}
					return holder;
				}

				@Override
				public DensityFunction apply(DensityFunction input) {
					if (input instanceof BlendedNoise blended && !registry.blendedNoises.containsKey(blended)) {
						registry.blendedNoises.put(blended, "blended:" + registry.blendedNoises.size());
					}
					return input;
				}
			};
			for (DensityFunction function : functions(router)) {
				function.mapAll(visitor);
			}
			return registry;
		}

		private String requireId(SeededLeafTranscript.Kind kind, Object leaf) {
			String id = switch (kind) {
				case NORMAL_NOISE -> normalNoises.get(leaf);
				case BLENDED_NOISE -> blendedNoises.get(leaf);
			};
			if (id == null) {
				throw new TraceMismatchException("Seed-dependent leaf is outside the registered NoiseRouter: " + kind);
			}
			return id;
		}

		private static List<DensityFunction> functions(NoiseRouter router) {
			return List.of(
				router.barrierNoise(),
				router.fluidLevelFloodednessNoise(),
				router.fluidLevelSpreadNoise(),
				router.lavaNoise(),
				router.temperature(),
				router.vegetation(),
				router.continents(),
				router.erosion(),
				router.depth(),
				router.ridges(),
				router.preliminarySurfaceLevel(),
				router.finalDensity(),
				router.veinToggle(),
				router.veinRidged(),
				router.veinGap()
			);
		}
	}
}
