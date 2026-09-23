package io.github.genichimaruo.worldgenassist.common;

public record WorldgenProtocolVersion(int value) {
	public static final int MIN_VALUE = 1;
	public static final int MAX_VALUE = 65_535;
	public static final WorldgenProtocolVersion CURRENT = new WorldgenProtocolVersion(3);

	public WorldgenProtocolVersion {
		if (value < MIN_VALUE || value > MAX_VALUE) {
			throw new IllegalArgumentException(
				"Protocol version must be between " + MIN_VALUE + " and " + MAX_VALUE + ": " + value
			);
		}
	}

	public boolean isSupported() {
		return equals(CURRENT);
	}

	public void requireSupported() {
		if (!isSupported()) {
			throw new IllegalArgumentException("Unsupported worldgen protocol version: " + value);
		}
	}
}
