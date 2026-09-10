package io.github.genichimaruo.worldgenassist.common;

/** Explicit public-world experiment; never authorizes arbitrary private seeds. */
public final class SeededLeafFixtureConfig {
	public static final int PROTOCOL = 3;
	public static final long PUBLIC_FIXTURE_SEED = 8675309L;
	public static final String SERVER_PROPERTY = "worldgen_assist.seeded_leaf.public_fixture";
	public static final String CLIENT_PROPERTY = "worldgen_assist.client.seeded_leaf.public_fixture";
	private SeededLeafFixtureConfig() { }
	public static boolean serverEnabled() {
		return flag(SERVER_PROPERTY, "WORLDGEN_ASSIST_SEEDED_LEAF_PUBLIC_FIXTURE");
	}
	public static boolean clientEnabled() {
		return flag(CLIENT_PROPERTY, "WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_PUBLIC_FIXTURE");
	}
	public static boolean payloadsEnabled() { return serverEnabled() || clientEnabled(); }
	public static boolean permitsWorld(long seed) { return serverEnabled() && seed == PUBLIC_FIXTURE_SEED; }
	private static boolean flag(String property, String environment) {
		String value = System.getProperty(property);
		if (value == null) { value = System.getenv(environment); }
		if (value == null || "false".equalsIgnoreCase(value)) { return false; }
		if ("true".equalsIgnoreCase(value)) { return true; }
		throw new IllegalArgumentException("Expected true/false for " + property);
	}
}
