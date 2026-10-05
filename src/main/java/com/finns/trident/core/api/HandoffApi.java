package com.finns.trident.core.api;

import com.finns.trident.core.BusinessException;
import com.finns.trident.core.ErrorCode;
import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.Handoff;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Issues a one-time code that signs the signed-in customer in to the booking website, so they don't
 * sign in twice. The app opens booking with the code; booking redeems it through {@link BookingApi}.
 */
@Path(QrApi.PREFIX + "/handoffs")
public class HandoffApi {
	private static final Logger logger = Logger.getLogger(HandoffApi.class);

	/** Longest values kept from the token, matching the {@code handoff} columns, so an odd claim can't fail the insert. */
	private static final int MAX_NAME = 128;

	private static final int MAX_EMAIL = 320;

	private static final int MAX_SID = 128;

	@Inject
	FinnsConfig config;

	/** The customer's access token, already verified. */
	@Inject
	JsonWebToken token;

	@RegisterForReflection
	public record Issued(String code, long ttlSeconds) {
	}

	@POST
	@Transactional
	public Response issue() {
		String sub = token.getSubject();
		if (sub == null || sub.isBlank()) throw new NotAuthorizedException("Bearer");
		Instant now = Instant.now();
		Customer customer = Customer.ofSubject(sub, now);
		String name = claim("name", MAX_NAME);
		if (name == null) name = claim("preferred_username", MAX_NAME);
		Handoff.Issued issued = Handoff.issue(customer, claim("sid", MAX_SID), name, claim("email", MAX_EMAIL),
				config.booking().handoffTtl(), now)
				.orElseThrow(() -> new BusinessException(ErrorCode.RATE_LIMITED));
		logger.infof("handoff.issued handoffId=%s customerId=%d", issued.id(), customer.id);
		return Response.status(Response.Status.CREATED)
				.header("Cache-Control", "no-store")
				.entity(new Issued(issued.code(), config.booking().handoffTtl().toSeconds()))
				.build();
	}

	/** A string claim of the token, trimmed and cut to {@code max}; null if absent or blank. */
	private String claim(String name, int max) {
		Object value = token.getClaim(name);
		if (!(value instanceof String s) || s.isBlank()) return null;
		s = s.strip();
		return s.length() > max ? s.substring(0, max) : s;
	}
}
