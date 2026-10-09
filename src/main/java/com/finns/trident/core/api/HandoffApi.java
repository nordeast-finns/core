package com.finns.trident.core.api;

import com.finns.trident.core.BusinessException;
import com.finns.trident.core.ErrorCode;
import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.Handoff;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
		Instant now = Instant.now();
		Customer customer = AppToken.customer(token, now);
		Handoff.Issued issued = Handoff.issue(customer, AppToken.sid(token), customer.displayName,
				customer.email, config.booking().handoffTtl(), now)
				.orElseThrow(() -> new BusinessException(ErrorCode.RATE_LIMITED));
		logger.infof("handoff.issued handoffId=%s customerId=%d", issued.id(), customer.id);
		return Response.status(Response.Status.CREATED)
				.header("Cache-Control", "no-store")
				.entity(new Issued(issued.code(), config.booking().handoffTtl().toSeconds()))
				.build();
	}
}
