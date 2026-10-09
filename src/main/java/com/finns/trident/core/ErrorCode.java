package com.finns.trident.core;

/**
 * Every business error the API can return. {@code code} is the stable wire value clients switch
 * on; clients own the wording people see.
 */
public enum ErrorCode {
	MALFORMED("malformed", 400),
	FORBIDDEN("forbidden", 403),
	NOT_FOUND("not_found", 404),
	EMAIL_TAKEN("email_taken", 409),
	LAST_ADMIN("last_admin", 409),
	INSUFFICIENT_POINTS("insufficient_points", 409),
	ALREADY_REFUNDED("already_refunded", 409),
	NOT_REFUNDABLE("not_refundable", 409),
	SELF_CHANGE("self_change", 409),
	STALE("stale", 412),
	INVALID("invalid", 422),
	/** An Idempotency-Key reused for a different request. */
	IDEMPOTENCY_MISMATCH("idempotency_mismatch", 422),
	PRECONDITION_REQUIRED("precondition_required", 428),
	RATE_LIMITED("rate_limited", 429),
	/** A service core depends on (Keycloak) couldn't be reached; try again. */
	UNAVAILABLE("unavailable", 503),
	;

	public final String code;

	public final int status;

	ErrorCode(String code, int status) {
		this.code = code;
		this.status = status;
	}
}
