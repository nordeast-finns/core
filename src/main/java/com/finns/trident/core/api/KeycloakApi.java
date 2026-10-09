package com.finns.trident.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.finns.trident.core.BusinessException;
import com.finns.trident.core.CustomerSync;
import com.finns.trident.core.KeycloakUsers;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import static com.finns.trident.core.ErrorCode.MALFORMED;
import static com.finns.trident.core.ErrorCode.UNAVAILABLE;

/**
 * Called by core's Keycloak extension ({@code keycloak-extension/}) after a user's change commits: sign-up,
 * sign-in, a profile or email change, an admin's edit, deletion. The notice names the user only; core reads
 * the user from Keycloak itself ({@link CustomerSync#user}), so the notice can't carry a wrong or stale copy.
 */
@Path(KeycloakApiFilter.PREFIX)
public class KeycloakApi {
	private static final Logger logger = Logger.getLogger(KeycloakApi.class);

	/** Keycloak's user ids: printable ASCII without spaces, as long as {@code customer.keycloak_sub} allows. */
	private static final String USER_ID = "[\\x21-\\x7e]{1,128}";

	@Inject
	CustomerSync sync;

	/** {@code userId} is a JSON node so a number is refused rather than coerced. */
	@RegisterForReflection
	public record UserChange(JsonNode userId) {
	}

	/** 204 once core's copy matches Keycloak; 503 if Keycloak couldn't be read, so the extension retries. */
	@POST
	@Path("/user-changes")
	public Response userChanged(UserChange body) {
		if (body == null || body.userId() == null || !body.userId().isTextual()
				|| !body.userId().asText().matches(USER_ID)) {
			throw new BusinessException(MALFORMED);
		}
		try {
			sync.user(body.userId().asText());
		} catch (KeycloakUsers.UnavailableException e) {
			logger.warnf("keycloak.sync_failed reason=%s", e.getMessage());
			throw new BusinessException(UNAVAILABLE);
		}
		return Response.noContent().build();
	}
}
