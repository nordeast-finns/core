package com.finns.trident.core.api;

import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsAccount;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;

/**
 * The signed-in customer's own points, for the customer app. Needs the customer's Keycloak access token,
 * like everything under {@link QrApi#PREFIX}.
 */
@Path(QrApi.PREFIX + "/points")
public class AppPointsApi {
	/** The customer's access token, already verified. */
	@Inject
	JsonWebToken token;

	/** {@code balance} is 0 before the customer's first posting. */
	@RegisterForReflection
	public record Balance(long balance) {
	}

	@GET
	@Transactional
	public Response balance() {
		Customer customer = AppToken.customer(token, Instant.now());
		long balance = PointsAccount.ofCustomer(customer.id).map(a -> a.balance).orElse(0L);
		return PointsApi.noStore(Response.ok(new Balance(balance)));
	}
}
