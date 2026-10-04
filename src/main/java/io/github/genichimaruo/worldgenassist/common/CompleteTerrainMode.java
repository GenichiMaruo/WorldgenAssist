package io.github.genichimaruo.worldgenassist.common;

/** Separate operator choice for whole-chunk trusted-friend audits. Remote and seed gates still apply. */
public final class CompleteTerrainMode {
	private CompleteTerrainMode() { }
	public static boolean requested() {
		String kind = System.getProperty("worldgen_assist.remote.work_kind", System.getenv("WORLDGEN_ASSIST_REMOTE_WORK_KIND"));
		String allowed = System.getProperty("worldgen_assist.remote.allow_complete_terrain", System.getenv("WORLDGEN_ASSIST_REMOTE_ALLOW_COMPLETE_TERRAIN"));
		return "complete".equalsIgnoreCase(kind) && "true".equalsIgnoreCase(allowed);
	}
}
