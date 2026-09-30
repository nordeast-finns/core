package com.finns.trident.core;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Application config under {@code finns.*}. Invalid values fail startup rather than serving with a
 * weak or missing secret.
 */
@ConfigMapping(prefix = "finns")
public interface FinnsConfig {
	AdminApi adminApi();

	GateApi gateApi();

	Staff staff();

	Qr qr();

	interface AdminApi {
		/** Bearer token the Admin Console Worker sends on every {@code /api/v1/admin/*} call. */
		@Size(min = 32)
		String token();
	}

	interface GateApi {
		/** Bearer token every gate device sends on {@code /api/v1/gate/*}. One shared token for now. */
		@Size(min = 32)
		String token();
	}

	interface Staff {
		/** Emails made active ADMINs at startup while no active ADMIN exists. */
		Optional<List<String>> bootstrapAdmins();
	}

	interface Qr {
		/** How long an issued QR code can be checked in. */
		@WithDefault("60s")
		Duration ttl();
	}
}
