package io.github.genichimaruo.worldgenassist.server;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;

/** Read-only, server-thread inspection after a slow diagnostic shutdown. */
final class ShutdownWorkDiagnostic {

	static void record(MinecraftServer server) {
		if (!server.isSameThread()) return;
		try {
			WorldgenAssist.LOGGER.warn("[CAWG] shutdown.clock now={} next={} delayed={} delayed_enabled={} reentrant={} blocking={}",
				System.nanoTime(), field(server,"nextTickTimeNanos"), field(server,"delayedTasksMaxNextTickTimeNanos"),
				field(server,"mayHaveDelayedTasks"), field(server,"reentrantCount"), field(server,"blockingCount"));
			for (var level : server.getAllLevels()) {
				var map = level.getChunkSource().chunkMap;
				Map<?,?> updating=(Map<?,?>)field(map,"updatingChunkMap");
				Map<?,?> pending=(Map<?,?>)field(map,"pendingUnloads");
				WorldgenAssist.LOGGER.warn("[CAWG] shutdown.work dimension={} updating={} pending={} drop={} unload={} generation_tasks={} light={} worldgen_dispatch={} light_dispatch={} tickets={}",
					level.dimension().identifier(), updating.size(), pending.size(), ((java.util.Collection<?>)field(map,"toDrop")).size(),
					((java.util.Collection<?>)field(map,"unloadQueue")).size(),
					((java.util.Collection<?>)field(map,"pendingGenerationTasks")).size(),
					level.getChunkSource().getLightEngine().hasLightWork(), work(field(map,"worldgenTaskDispatcher")),
					work(field(map,"lightTaskDispatcher")), work(field(map,"distanceManager")));
				int shown=0;
				for (Object value : pending.values()) {
					ChunkHolder holder=(ChunkHolder)value;
					if(holder.isReadyForSaving()) continue;
					WorldgenAssist.LOGGER.warn("[CAWG] shutdown.holder chunk={} status={} references={} task={} futures={}",
						holder.getPos(),holder.getLatestStatus(),((AtomicInteger)field(holder,"generationRefCount")).get(),
						field(holder,"task"),holder.getAllFutures());
					if(++shown==3) break;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException error) {
			WorldgenAssist.LOGGER.warn("[CAWG] shutdown.work_unavailable",error);
		}
	}

	private static Object field(Object target,String name) throws ReflectiveOperationException {
		for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass()) {
			try { var field=type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
			catch(NoSuchFieldException ignored) { }
		}
		throw new NoSuchFieldException(name);
	}
	private static Object work(Object target) throws ReflectiveOperationException {
		String method=target instanceof net.minecraft.server.level.DistanceManager ? "hasTickets" : "hasWork";
		var accessor=target.getClass().getMethod(method); accessor.setAccessible(true); return accessor.invoke(target);
	}
}
