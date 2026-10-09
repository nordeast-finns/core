package com.finns.trident.core.api;

import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.Qr;
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
 * Issues check-in QR codes to the signed-in customer. Like everything under {@link #PREFIX}, it needs
 * the customer's Keycloak access token, which Quarkus checks before routing (see
 * {@code quarkus.http.auth.permission.app} in {@code application.properties}).
 */
@Path(QrApi.PREFIX + "/qrs")
public class QrApi {
	/** The customer app's API. */
	public static final String PREFIX = "/api/v1/app";

	private static final Logger logger = Logger.getLogger(QrApi.class);

	@Inject
	FinnsConfig config;

	/** The customer's access token, already verified. */
	@Inject
	JsonWebToken token;

	/**
	 * {@code qr} is the exact text to encode; clients render it without parsing. {@code ttlSeconds}
	 * lets them count down on their own clock, whatever its skew.
	 */
	@RegisterForReflection
	public record Issued(String qr, Instant expiresAt, long ttlSeconds) {
	}

	@POST
	@Transactional
	public Response issue() {
		Instant now = Instant.now();
		Customer customer = AppToken.customer(token, now);
		Qr.Issued issued = Qr.issue(customer.id, config.qr().ttl(), now);
		logger.infof("qr.issued qrId=%s customerId=%d", issued.id(), customer.id);
		return Response.status(Response.Status.CREATED)
				.entity(new Issued(issued.qr(), issued.expiresAt(), config.qr().ttl().toSeconds()))
				.build();
	}
}
