package com.finns.trident.core.api;

import com.finns.trident.core.BookingNotifier;
import com.finns.trident.core.model.Handoff;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;

/**
 * Called by the app as the customer signs out, so the booking website signs them out too. The session
 * is the token's own {@code sid}, never one the caller names, so a customer can only end their own.
 */
@Path(QrApi.PREFIX + "/logout")
public class LogoutApi {
	@Inject
	BookingNotifier booking;

	/** The customer's access token, already verified. */
	@Inject
	JsonWebToken token;

	@POST
	@Transactional
	public Response logout() {
		if (token.getSubject() == null) throw new NotAuthorizedException("Bearer");
		Object sid = token.getClaim("sid");
		if (sid instanceof String s && !s.isBlank()) {
			Handoff.revokeSession(s, Instant.now());
			booking.sessionEnded(s);
		}
		return Response.noContent().build();
	}
}
