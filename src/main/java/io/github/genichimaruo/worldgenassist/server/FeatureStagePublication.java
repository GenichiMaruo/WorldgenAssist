package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Process-local lifetime of the ORIGINAL applied stage, including its status callback. */
public final class FeatureStagePublication {
	private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
	private final CompletableFuture<Void> published = new CompletableFuture<>();

	private FeatureStagePublication() { }

	public static <T> CompletableFuture<T> around(Object context, Object step, Object chunk,
		Supplier<CompletableFuture<T>> original) {
		Binding previous = CURRENT.get();
		var publication = new FeatureStagePublication();
		CURRENT.set(new Binding(context, step, chunk, publication));
		try {
			// Original ChunkStep.apply installs/executes completeChunkGeneration here.
			// A completed BODY is insufficient: this future includes original status/profiler publication.
			CompletableFuture<T> applied = Objects.requireNonNull(original.get(), "original stage returned null");
			applied.whenComplete((value, error) -> publication.complete(error));
			// Cancelling an observer must not cancel the original status callback.
			return applied.copy();
		} catch (Throwable failure) {
			publication.complete(failure);
			throw failure;
		} finally {
			if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
		}
	}

	static FeatureStagePublication require(Object context, Object step, Object chunk) {
		Binding binding = CURRENT.get();
		if (binding == null || binding.context != context || binding.step != step || binding.chunk != chunk)
			throw new IllegalStateException("owned generation outside matching original ChunkStep publication scope");
		return binding.publication;
	}

	void whenPublished(BiConsumer<Void, Throwable> completion) { published.whenComplete(completion); }
	private void complete(Throwable error) {
		if (error == null) published.complete(null); else published.completeExceptionally(error);
	}
	private record Binding(Object context, Object step, Object chunk, FeatureStagePublication publication) { }
}
