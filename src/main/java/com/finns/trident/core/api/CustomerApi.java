package com.finns.trident.core.api;

import com.finns.trident.core.BusinessException;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.Staff;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestQuery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finns.trident.core.ErrorCode.FORBIDDEN;

/**
 * Customers and their points, for the Admin Console. Any active staff member can read them. Customers
 * are shown by {@link Customer#publicId}, the id points partners know them by: Keycloak owns their
 * profile, and core keeps no name or email.
 */
@Path(AdminApiFilter.PREFIX + "/customers")
public class CustomerApi {
	static final int MAX_PAGE_SIZE = 200;

	@HeaderParam(AdminApiFilter.ACTOR_SUB_HEADER)
	String actorSub;

	@RegisterForReflection
	public record View(UUID customerId, long balance, Instant createdAt) {
		static View of(Customer.WithBalance c) {
			return new View(c.publicId(), c.balance(), c.createdAt());
		}
	}

	@RegisterForReflection
	public record Page(List<View> items, long total, int page, int size) {
	}

	/** Newest first. */
	@GET
	public Response list(@RestQuery Integer page, @RestQuery Integer size) {
		requireStaff();
		int p = page == null ? 1 : Math.max(1, page);
		int n = size == null ? 50 : Math.clamp(size, 1, MAX_PAGE_SIZE);
		List<View> items = Customer.withBalances(p - 1, n).stream().map(View::of).toList();
		return Response.ok(new Page(items, Customer.count(), p, n)).header("Cache-Control", "no-store").build();
	}

	private void requireStaff() {
		if (actorSub == null || actorSub.isBlank()) throw new BusinessException(FORBIDDEN);
		Staff.findBySub(actorSub).filter(s -> s.active).orElseThrow(() -> new BusinessException(FORBIDDEN));
	}
}
