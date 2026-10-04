package io.github.genichimaruo.worldgenassist.server;

import io.github.genichimaruo.worldgenassist.common.CompleteTerrainMode;

/** Explicit server-owned trusted-friend choice. The default server audit policy is unchanged. */
final class CompleteTerrainVerificationMode {
	private CompleteTerrainVerificationMode() { }
	static boolean peerRequested() {
		String mode = System.getProperty("worldgen_assist.remote.complete_verification",
			System.getenv("WORLDGEN_ASSIST_REMOTE_COMPLETE_VERIFICATION"));
		return CompleteTerrainMode.requested() && "peer".equalsIgnoreCase(mode);
	}
}
