package com.finns.trident.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.finns.trident.core.BusinessException;
import com.finns.trident.core.ErrorCode;
import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.Handoff;
import com.finns.trident.core.model.PointsTxn;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.finns.trident.core.ErrorCode.INVALID;
import static com.finns.trident.core.ErrorCode.MALFORMED;

/**
 * What the booking website's server asks and tells core: who a handoff code (see {@link HandoffApi}) signs
 * in, and which bookings were paid. Every path under {@link BookingApiFilter#PREFIX} needs the booking
 * website's bearer token.
 */
@Path(BookingApiFilter.PREFIX)
public class BookingApi {
	private static final Logger logger = Logger.getLogger(BookingApi.class);

	/** Printable ASCII without spaces, matching the {@code customer.keycloak_sub} column. */
	static final Pattern SUB = Pattern.compile("[\\x21-\\x7e]{1,128}");

	static final Pattern BOOKING_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	@Inject
	FinnsConfig config;

	@RegisterForReflection
	public record Code(String code) {
	}

	@RegisterForReflection
	public record Peeked(String displayName, String email) {
	}

	@RegisterForReflection
	public record Redeemed(String sub, String sid, String displayName, String email) {
	}

	/**
	 * A paid booking. {@code sub} is the Keycloak subject booking signed the customer in with, and
	 * {@code totalIdr} a JSON node so a fraction or a numeric string is refused rather than coerced.
	 */
	@RegisterForReflection
	public record Booking(String sub, String bookingId, JsonNode totalIdr) {
	}

	/** Who the code would sign in, without using it, for booking's confirmation page. */
	@POST
	@Path("/handoffs/peek")
	@Transactional
	public Response peek(Code body) {
		Handoff.Peeked p = Handoff.peek(body == null ? null : body.code(), Instant.now())
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return noStore(new Peeked(p.displayName(), p.email()));
	}

	/** Uses the code. Any code that can't be used (unknown, used, expired, revoked) is the same 404. */
	@POST
	@Path("/handoffs/redeem")
	@Transactional
	public Response redeem(Code body) {
		Handoff.Redeemed r = Handoff.redeem(body == null ? null : body.code(), Instant.now())
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return noStore(new Redeemed(r.sub(), r.sid(), r.displayName(), r.email()));
	}

	/**
	 * Credits the customer for a paid booking: 1 point per {@code finns.points.earn.booking-idr-per-point}
	 * of its total, rounded down, once per booking id. A repeat of the same booking changes nothing. A total
	 * worth more than one posting can hold ({@link PointsApi#MAX_POINTS}) is invalid.
	 */
	@POST
	@Path("/bookings")
	@Transactional
	public Response booked(Booking body) {
		if (body == null) throw new BusinessException(MALFORMED);
		Map<String, String> errors = new HashMap<>();
		check("sub", body.sub(), SUB, errors);
		check("bookingId", body.bookingId(), BOOKING_ID, errors);
		long points = 0;
		JsonNode total = body.totalIdr();
		if (total == null || total.isNull()) {
			errors.put("totalIdr", "required");
		} else if (!total.isIntegralNumber() || !total.canConvertToLong() || total.longValue() < 0) {
			errors.put("totalIdr", "invalid");
		} else {
			points = total.longValue() / config.points().earn().bookingIdrPerPoint();
			if (points > PointsApi.MAX_POINTS) errors.put("totalIdr", "invalid");
		}
		if (!errors.isEmpty()) throw new BusinessException(INVALID, errors);

		Instant now = Instant.now();
		Customer customer = Customer.ofSubject(body.sub(), now);
		boolean replayed = points > 0 && PointsTxn.earn(customer, points, PointsTxn.BOOKING_REASON, body.bookingId(),
				now, config.points().timeZone()).replayed();
		logger.infof("booking.recorded customerId=%d points=%d replayed=%b", customer.id, points, replayed);
		return Response.noContent().header("Cache-Control", "no-store").build();
	}

	private static void check(String field, String value, Pattern pattern, Map<String, String> errors) {
		if (value == null || value.isEmpty()) {
			errors.put(field, "required");
		} else if (!pattern.matcher(value).matches()) {
			errors.put(field, "invalid");
		}
	}

	private static Response noStore(Object entity) {
		return Response.ok(entity).header("Cache-Control", "no-store").build();
	}
}
