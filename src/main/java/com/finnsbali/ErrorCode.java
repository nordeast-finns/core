package com.finnsbali;

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
	SELF_CHANGE("self_change", 409),
	STALE("stale", 412),
	INVALID("invalid", 422),
	PRECONDITION_REQUIRED("precondition_required", 428),
	;

	public final String code;

	public final int status;

	ErrorCode(String code, int status) {
		this.code = code;
		this.status = status;
	}
}
