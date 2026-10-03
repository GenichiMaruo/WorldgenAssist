package io.github.genichimaruo.worldgenassist.server;

/** Server-thread lookup only; never adds tickets or schedules generation. */
public interface ReadyTerrainLookup {
	boolean worldgenAssist$terrainReady(int x,int z);
}
