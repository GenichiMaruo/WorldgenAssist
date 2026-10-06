package io.github.genichimaruo.worldgenassist.server;

/** Experimental scheduling only; no change to remote or seed-disclosure policy. */
public enum FeatureStageConfig {
	OFF(0), SERIAL(1), GUARDED(1), PARALLEL(2);
	public static final String PROPERTY = "worldgen_assist.feature_backend";
	public static final String ENVIRONMENT = "WORLDGEN_ASSIST_FEATURE_BACKEND";
	private static final FeatureStageConfig CURRENT = load();
	private final int workers;
	FeatureStageConfig(int workers) { this.workers = workers; }
	public int workers() { return workers; }
	/** Same conservative regional ownership, independent of the number of CPU workers. */
	public boolean usesRegionalOwnership() { return this == GUARDED || this == PARALLEL; }
	public static FeatureStageConfig current() { return CURRENT; }
	static FeatureStageConfig parse(String value) {
		if (value == null) return OFF;
		return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "serial" -> SERIAL;
			case "guarded" -> GUARDED;
			case "parallel" -> PARALLEL;
			default -> OFF;
		};
	}
	private static FeatureStageConfig load() {
		String value = System.getProperty(PROPERTY);
		return parse(value == null || value.isBlank() ? System.getenv(ENVIRONMENT) : value);
	}
}
