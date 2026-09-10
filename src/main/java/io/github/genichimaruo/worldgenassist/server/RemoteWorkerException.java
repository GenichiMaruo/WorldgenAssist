package io.github.genichimaruo.worldgenassist.server;

import java.util.Objects;

import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;

public final class RemoteWorkerException extends RuntimeException {
	private final TerrainJobFailurePayload.Reason reason;

	public RemoteWorkerException(TerrainJobFailurePayload.Reason reason) {
		super("Remote worker rejected terrain job: " + Objects.requireNonNull(reason, "reason"));
		this.reason = reason;
	}

	public TerrainJobFailurePayload.Reason reason() {
		return reason;
	}
}
