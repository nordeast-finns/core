package com.finns.trident.core.api;

import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.Hashes;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.security.MessageDigest;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;

/**
 * Authenticates every {@code /api/v1/gate/*} request, including paths that match no endpoint, with
 * the gate devices' bearer token. Works like {@link AdminApiFilter}.
 */
public class GateApiFilter {
	public static final String PREFIX = "/api/v1/gate";

	private static final Logger logger = Logger.getLogger(GateApiFilter.class);

	private final byte[] expected;

	@Inject
	public GateApiFilter(FinnsConfig config) {
		expected = Hashes.sha256("Bearer " + config.gateApi().token());
	}

	@ServerRequestFilter(preMatching = true)
	public Response authenticate(ContainerRequestContext ctx) {
		String path = ctx.getUriInfo().getPath();
		if (!path.equals(PREFIX) && !path.startsWith(PREFIX + "/")) return null;

		String header = ctx.getHeaderString(AUTHORIZATION);
		// Comparing fixed-length digests keeps the check constant-time, including for length.
		if (header != null && MessageDigest.isEqual(expected, Hashes.sha256(header))) {
			return null;
		}
		logger.warnf("gate_api.bad_token method=%s path=%s", ctx.getMethod(), path);
		return Response.status(UNAUTHORIZED).build();
	}
}
