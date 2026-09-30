package com.finns.trident.core.api;

import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.model.Qr;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Issues check-in QR codes to the customer app. Unauthenticated for now: QR codes carry no identity,
 * so anyone can get one. Nothing that controls a real door may rely on them until that changes.
 */
@Path(QrApi.PREFIX + "/qrs")
public class QrApi {
	/** The customer app's API. */
	public static final String PREFIX = "/api/v1/app";

	private static final Logger logger = Logger.getLogger(QrApi.class);

	@Inject
	FinnsConfig config;

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
		Qr.Issued issued = Qr.issue(config.qr().ttl(), Instant.now());
		logger.infof("qr.issued qrId=%s", issued.id());
		return Response.status(Response.Status.CREATED)
				.entity(new Issued(issued.qr(), issued.expiresAt(), config.qr().ttl().toSeconds()))
				.build();
	}
}
