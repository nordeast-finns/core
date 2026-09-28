package com.finnsbali;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;

/**
 * Application config under {@code finns.*}. Invalid values fail startup rather than serving with a
 * weak or missing secret.
 */
@ConfigMapping(prefix = "finns")
public interface FinnsConfig {
	AdminApi adminApi();

	Staff staff();

	interface AdminApi {
		/** Bearer token the Admin Console Worker sends on every {@code /api/v1/admin/*} call. */
		@Size(min = 32)
		String token();
	}

	interface Staff {
		/** Emails made active ADMINs at startup while no active ADMIN exists. */
		Optional<List<String>> bootstrapAdmins();
	}
}
