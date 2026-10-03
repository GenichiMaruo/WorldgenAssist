package io.github.genichimaruo.worldgenassist.server;

/** Development switches for paired measurements; remote policy still gates every dispatch. */
record RemotePipelineOptions(boolean prefetch, boolean prepareValidation, long demandWaitMillis, boolean adaptiveDemandWait,
	boolean readySurfaceOnly) {
	RemotePipelineOptions(boolean prefetch, boolean prepareValidation, long demandWaitMillis, boolean adaptiveDemandWait) {
		this(prefetch, prepareValidation, demandWaitMillis, adaptiveDemandWait, true);
	}
	static RemotePipelineOptions current() {
		String wait = value("demand_wait_ms");
		long milliseconds = wait == null ? 100L : Long.parseLong(wait);
		if (milliseconds < 5 || milliseconds > 1000) throw new IllegalArgumentException("demand_wait_ms must be 5..1000");
		return new RemotePipelineOptions(flag("prefetch", true), flag("prepare_validation", true), milliseconds,
			flag("adaptive_demand_wait", true), flag("ready_surface_only", true));
	}
	static int prefetchLookahead() {
		String configured = value("prefetch_lookahead");
		int count = configured == null ? 0 : Integer.parseInt(configured);
		if (count < 0 || count > 64) throw new IllegalArgumentException("prefetch_lookahead must be 0..64");
		return count;
	}
	static int ownerWindow() {
		String configured = value("owner_window");
		int count = configured == null ? 4 : Integer.parseInt(configured);
		if (count < 1 || count > 64) throw new IllegalArgumentException("owner_window must be 1..64");
		return count;
	}
	static int refillWatermark(int total, int ownerWindow) {
		return Math.min(total, ownerWindow <= 4 ? 2 : ownerWindow);
	}
	private static boolean flag(String name, boolean fallback) {
		String value = value(name);
		return value == null ? fallback : Boolean.parseBoolean(value);
	}
	private static String value(String name) {
		String property = System.getProperty("worldgen_assist.remote." + name);
		if (property != null) return property;
		return System.getenv("WORLDGEN_ASSIST_REMOTE_" + name.toUpperCase(java.util.Locale.ROOT));
	}
}
