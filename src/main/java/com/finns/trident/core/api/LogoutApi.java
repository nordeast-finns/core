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
 * Called by the app as the customer signs out, so the booking website signs them out too, everywhere.
 * The customer and session are the token's own {@code sub} and {@code sid}, never ones the caller
 * names, so a customer can only sign themselves out.
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
		String sub = token.getSubject();
		if (sub == null || sub.isBlank()) throw new NotAuthorizedException("Bearer");
		String sid = AppToken.sid(token);
		if (sid != null) Handoff.revokeSession(sid, Instant.now());
		booking.signedOut(sub, sid);
		return Response.noContent().build();
	}
}
