package com.finns.trident.core;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
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

	Booking booking();

	Points points();

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

	interface Booking {
		/** Bearer token the booking website sends on every {@code /api/v1/booking/*} call. */
		@Size(min = 32)
		String apiToken();

		/** The booking website's origin, where core tells it a customer signed out of the app. */
		URI url();

		/** Bearer token core sends to the booking website's session-revoke endpoint. */
		@Size(min = 32)
		String revokeToken();

		/** How long a handoff code can be redeemed. */
		@WithDefault("60s")
		Duration handoffTtl();
	}

	interface Points {
		/** Bearer token Points API clients (Sota) send on every {@code /api/v1/points/*} call. */
		@Size(min = 32)
		String apiToken();

		/** Where a transaction's business date is taken, for reconciliation: Bali. */
		@WithDefault("Asia/Makassar")
		ZoneId timeZone();

		/** When the ledger integrity check runs, in UTC: 02:30 in Bali. */
		@WithDefault("0 30 18 * * ?")
		String checkCron();
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
