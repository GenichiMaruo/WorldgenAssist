package io.github.genichimaruo.worldgenassist.server;

/** Development switches for paired measurements; remote policy still gates every dispatch. */
record RemotePipelineOptions(boolean prefetch, boolean prepareValidation, long demandWaitMillis, boolean adaptiveDemandWait) {
	static RemotePipelineOptions current() {
		String wait = value("demand_wait_ms");
		long milliseconds = wait == null ? 100L : Long.parseLong(wait);
		if (milliseconds < 5 || milliseconds > 1000) throw new IllegalArgumentException("demand_wait_ms must be 5..1000");
		return new RemotePipelineOptions(flag("prefetch", true), flag("prepare_validation", true), milliseconds,
			flag("adaptive_demand_wait", true));
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
