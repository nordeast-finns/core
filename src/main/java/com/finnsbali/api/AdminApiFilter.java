package com.finnsbali.api;

import com.finnsbali.FinnsConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;

/**
 * Authenticates every {@code /api/v1/admin/*} request, including paths that match no endpoint, with
 * the Admin Console Worker's bearer token. Runs before routing, so new admin endpoints are protected
 * by default and unauthenticated callers can't probe which ones exist.
 */
public class AdminApiFilter {
	public static final String PREFIX = "/api/v1/admin";

	/** The acting staff member's JumpCloud subject, on calls the Worker makes on their behalf. */
	public static final String ACTOR_SUB_HEADER = "X-Finns-Actor-Sub";

	private static final Logger logger = Logger.getLogger(AdminApiFilter.class);

	private final byte[] expected;

	@Inject
	public AdminApiFilter(FinnsConfig config) {
		expected = sha256("Bearer " + config.adminApi().token());
	}

	@ServerRequestFilter(preMatching = true)
	public Response authenticate(ContainerRequestContext ctx) {
		String path = ctx.getUriInfo().getPath();
		if (!path.equals(PREFIX) && !path.startsWith(PREFIX + "/")) return null;

		String header = ctx.getHeaderString(AUTHORIZATION);
		// Comparing fixed-length digests keeps the check constant-time, including for length.
		if (header != null && MessageDigest.isEqual(expected, sha256(header))) {
			return null;
		}
		logger.warnf("admin_api.bad_token method=%s path=%s", ctx.getMethod(), path);
		return Response.status(UNAUTHORIZED).build();
	}

	private static byte[] sha256(String s) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
