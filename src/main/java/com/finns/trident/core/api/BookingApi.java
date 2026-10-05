package com.finns.trident.core.api;

import com.finns.trident.core.BusinessException;
import com.finns.trident.core.ErrorCode;
import com.finns.trident.core.model.Handoff;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

/**
 * What the booking website's server asks core: who a handoff code (see {@link HandoffApi}) signs in.
 * Every path under {@link BookingApiFilter#PREFIX} needs the booking website's bearer token.
 */
@Path(BookingApiFilter.PREFIX + "/handoffs")
public class BookingApi {
	@RegisterForReflection
	public record Code(String code) {
	}

	@RegisterForReflection
	public record Peeked(String displayName) {
	}

	@RegisterForReflection
	public record Redeemed(String sub, String sid, String displayName, String email) {
	}

	/** Who the code would sign in, without using it, for booking's confirmation page. */
	@POST
	@Path("/peek")
	@Transactional
	public Response peek(Code body) {
		String name = Handoff.peek(body == null ? null : body.code(), Instant.now())
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return noStore(new Peeked(name));
	}

	/** Uses the code. Any code that can't be used (unknown, used, expired, revoked) is the same 404. */
	@POST
	@Path("/redeem")
	@Transactional
	public Response redeem(Code body) {
		Handoff.Redeemed r = Handoff.redeem(body == null ? null : body.code(), Instant.now())
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return noStore(new Redeemed(r.sub(), r.sid(), r.displayName(), r.email()));
	}

	private static Response noStore(Object entity) {
		return Response.ok(entity).header("Cache-Control", "no-store").build();
	}
}
