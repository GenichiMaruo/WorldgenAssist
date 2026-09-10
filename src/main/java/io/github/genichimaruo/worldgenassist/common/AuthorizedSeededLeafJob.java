package io.github.genichimaruo.worldgenassist.common;

import java.util.Objects;

/** Unregistered wire envelope binding a seeded-leaf job to a server-issued authentication tag. */
public record AuthorizedSeededLeafJob(
	SeededLeafJob job,
	SeededLeafJobAuthenticationTag authenticationTag
) {
	public static final int FORMAT_VERSION = 1;

	public AuthorizedSeededLeafJob {
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(authenticationTag, "authenticationTag");
	}
}
