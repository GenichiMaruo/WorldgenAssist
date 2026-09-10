package io.github.genichimaruo.worldgenassist.common;

import java.util.Objects;
import java.util.UUID;

/** Minimal client echo used to claim one server-retained seeded-leaf job. */
public record SeededLeafJobClaim(
	UUID jobId,
	OpaqueWorldgenContextId contextId,
	SeededLeafJobAuthenticationTag authenticationTag
) {
	public static final int FORMAT_VERSION = 1;

	public SeededLeafJobClaim {
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(contextId, "contextId");
		Objects.requireNonNull(authenticationTag, "authenticationTag");
		if (jobId.getMostSignificantBits() == 0L && jobId.getLeastSignificantBits() == 0L) {
			throw new IllegalArgumentException("Job ID must not be the all-zero UUID");
		}
	}

	public static SeededLeafJobClaim fromAuthorization(AuthorizedSeededLeafJob authorization) {
		Objects.requireNonNull(authorization, "authorization");
		return authorization.job().claim(authorization.authenticationTag());
	}
}
