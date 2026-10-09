package com.finns.trident.core.api;

import com.finns.trident.core.model.Customer;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;

/** What the customer app's endpoints read from the customer's access token, which Quarkus already verified. */
final class AppToken {
	/** Matches the {@code handoff.keycloak_sid} column. */
	private static final int MAX_SID = 128;

	private AppToken() {
	}

	/**
	 * The token's customer, created on their first request, with the copy of their display name and email
	 * refreshed from the token.
	 */
	static Customer customer(JsonWebToken token, Instant now) {
		// Quarkus already requires a subject (quarkus.oidc.token.subject-required); this keeps a token
		// without one from ever becoming a customer.
		String sub = token.getSubject();
		if (sub == null || sub.isBlank()) throw new NotAuthorizedException("Bearer");
		return Customer.ofSubject(sub, profile(token), now);
	}

	/**
	 * The Keycloak session ({@code sid}), or null if the token has none. Read the same way wherever it's
	 * used, so revoking a session finds the handoff codes issued in it.
	 */
	static String sid(JsonWebToken token) {
		return claim(token, "sid", MAX_SID);
	}

	/**
	 * The {@code name} claim, else {@code preferred_username}, cut to fit, and {@code email}, dropped
	 * rather than cut if too long, since a cut email is a different, wrong one.
	 */
	private static Customer.Profile profile(JsonWebToken token) {
		String name = claim(token, "name", Customer.Profile.DISPLAY_NAME_MAX);
		if (name == null) name = claim(token, "preferred_username", Customer.Profile.DISPLAY_NAME_MAX);
		String email = claim(token, "email", Integer.MAX_VALUE);
		if (email != null && email.codePointCount(0, email.length()) > Customer.Profile.EMAIL_MAX) email = null;
		return new Customer.Profile(name, email);
	}

	/**
	 * A string claim without control characters (Postgres refuses NUL), trimmed and cut to {@code max}
	 * characters, which is how Postgres counts a varchar's length; null if absent or blank. So writing it
	 * can't fail, which matters because the profile is written on the customer's requests, check-in included.
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
