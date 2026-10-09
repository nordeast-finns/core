package com.finns.trident.core.api;

import com.finns.trident.core.model.Customer;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;

/**
 * What the customer app's endpoints read from the customer's access token, which Quarkus already verified.
 * Never the customer's copy of their name and email: Keycloak itself is its only source (see
 * {@link com.finns.trident.core.CustomerSync}), since a token can be minutes older than the account.
 */
final class AppToken {
	/** Longest values kept for a handoff, matching the {@code handoff} columns. */
	private static final int MAX_NAME = 128;

	private static final int MAX_EMAIL = 320;

	private static final int MAX_SID = 128;

	private AppToken() {
	}

	/** The token's customer, created on their first request if Keycloak's notice hasn't done it yet. */
	static Customer customer(JsonWebToken token, Instant now) {
		// Quarkus already requires a subject (quarkus.oidc.token.subject-required); this keeps a token
		// without one from ever becoming a customer.
		String sub = token.getSubject();
		if (sub == null || sub.isBlank()) throw new NotAuthorizedException("Bearer");
		return Customer.ofSubject(sub, now);
	}

	/**
	 * The Keycloak session ({@code sid}), or null if the token has none. Read the same way wherever it's
	 * used, so revoking a session finds the handoff codes issued in it.
	 */
	static String sid(JsonWebToken token) {
		return claim(token, "sid", MAX_SID);
	}

	/** The {@code name} claim, else {@code preferred_username}, cut to fit; for a handoff. */
	static String name(JsonWebToken token) {
		String name = claim(token, "name", MAX_NAME);
		return name != null ? name : claim(token, "preferred_username", MAX_NAME);
	}

	/** The {@code email} claim; for a handoff. Dropped rather than cut if too long: a cut email is a wrong one. */
	static String email(JsonWebToken token) {
		String email = claim(token, "email", Integer.MAX_VALUE);
		return email != null && email.codePointCount(0, email.length()) > MAX_EMAIL ? null : email;
	}

	/**
	 * A string claim without control characters (Postgres refuses NUL), trimmed and cut to {@code max}
	 * characters, which is how Postgres counts a varchar's length; null if absent or blank. So writing it
	 * can't fail the request.
	 */
	private static String claim(JsonWebToken token, String name, int max) {
		if (!(token.getClaim(name) instanceof String value)) return null;
		String s = value.codePoints().filter(c -> !Character.isISOControl(c))
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
				.toString().strip();
		if (s.isEmpty()) return null;
		return s.codePointCount(0, s.length()) > max ? s.substring(0, s.offsetByCodePoints(0, max)) : s;
	}
}
