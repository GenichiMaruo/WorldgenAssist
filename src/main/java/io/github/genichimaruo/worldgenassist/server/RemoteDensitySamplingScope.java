package io.github.genichimaruo.worldgenassist.server;

import java.util.function.Supplier;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Generation-thread scope, entered at execution and always restored on failure. */
public final class RemoteDensitySamplingScope {
	private static final ThreadLocal<RemoteDensityField> ACTIVE = new ThreadLocal<>();
	private RemoteDensitySamplingScope() { }
	public static RemoteDensityField current() { return ACTIVE.get(); }
	public static Supplier<ChunkAccess> wrap(ChunkAccess chunk, Supplier<ChunkAccess> task) {
		return () -> run((RemoteDensityTarget)chunk, task);
	}
	public static <T> T run(RemoteDensityTarget target, Supplier<T> task) {
			RemoteDensityField previous = ACTIVE.get();
			RemoteDensityField field = target.worldgenAssist$getRemoteDensity();
			RemoteDensityOpportunity opportunity = target.worldgenAssist$takeRemoteOpportunity();
			boolean claimed = false;
			if (field == null && opportunity != null) {
				try { field = opportunity.take(); claimed = field != null; }
				catch (RuntimeException error) {
					io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.warn("[CAWG] queued_density.rejected reason={}", error.toString());
				}
			}
			if (field == null) ACTIVE.remove(); else ACTIVE.set(field);
			long start = System.nanoTime(); Throwable failure = null;
			try { return task.get(); }
			catch (RuntimeException | Error error) { failure = error; throw error; }
			finally {
				if (field != null) target.worldgenAssist$clearRemoteDensity(field.jobId());
				if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
				if (claimed) {
					try { opportunity.complete(field, System.nanoTime() - start, failure); }
					catch (RuntimeException error) {
						io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.warn("[CAWG] queued_density.completion_failed reason={}", error.toString());
					}
				}
			}
	}
}
