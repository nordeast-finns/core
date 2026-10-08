package com.finns.trident.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.finns.trident.core.BusinessException;
import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsAccount;
import com.finns.trident.core.model.PointsEvent;
import com.finns.trident.core.model.PointsTxn;
import com.finns.trident.core.model.PointsTxn.Kind;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.finns.trident.core.ErrorCode.INVALID;
import static com.finns.trident.core.ErrorCode.MALFORMED;
import static com.finns.trident.core.ErrorCode.NOT_FOUND;

/**
 * The points ledger's API, for points partners (Sota Platforms) to credit, debit and refund customers'
 * points and keep their copy from the feed. Every path under {@link PointsApiFilter#PREFIX} needs the
 * Points API token. core itself doesn't post points.
 * <p>
 * Every customer is a points member, known here by {@link Customer#publicId} only. Postings need an
 * {@code Idempotency-Key}: a retry with the same key and request returns the original transaction,
 * marked {@code Idempotent-Replayed: true}, and never posts twice.
 */
@Path(PointsApiFilter.PREFIX)
public class PointsApi {
	private static final Logger logger = Logger.getLogger(PointsApi.class);

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	static final long MAX_POINTS = 1_000_000_000L;

	static final int MAX_EVENTS = 500;

	static final int DEFAULT_EVENTS = 100;

	/** Printable ASCII without spaces, matching the {@code points_txn} column lengths. */
	static final Pattern KEY = Pattern.compile("[\\x21-\\x7e]{1,128}");

	static final Pattern REFERENCE = KEY;

	static final Pattern REASON = Pattern.compile("[A-Za-z0-9_.:-]{1,32}");

	@Inject
	FinnsConfig config;

	@RegisterForReflection
	public record Balance(UUID customerId, long balance, long seq) {
	}

	/**
	 * {@code reason} and {@code reference} are the caller's own code and id, opaque to core.
	 * {@code points} is a JSON node so a fraction or a numeric string is refused rather than coerced.
	 */
	@RegisterForReflection
	public record Posting(String customerId, JsonNode points, String reason, String reference) {
	}

	@RegisterForReflection
	public record Refund(String reference) {
	}

	/** {@code points} is signed, from the customer's side; {@code balance} and {@code seq} are after it. */
	@RegisterForReflection
	@JsonInclude(NON_NULL)
	public record Transaction(UUID transactionId, String kind, UUID customerId, long points, long balance, long seq,
			String reason, String reference, UUID refundOf, Instant recordedAt) {
		static Transaction of(PointsTxn.Posted p) {
			return new Transaction(p.id(), lower(p.kind()), p.customerId(), p.points(), p.balance(), p.seq(), p.reason(),
					p.reference(), p.refundOf(), p.recordedAt());
		}
	}

	/** {@code transaction} is set for {@code points.posted}. */
	@RegisterForReflection
	@JsonInclude(NON_NULL)
	public record Event(String eventId, String type, UUID customerId, Instant at, Transaction transaction) {
	}

	@RegisterForReflection
	public record Events(List<Event> events, String next) {
	}

	@RegisterForReflection
	public record Totals(long count, long points) {
	}

	/** {@code points} in each total is the kind's net effect on balances. */
	@RegisterForReflection
	public record Reconciliation(LocalDate businessDate, String timeZone, Totals credits, Totals debits, Totals refunds,
			String transactionsSha256) {
	}

	@GET
	@Path("/customers/{customerId}")
	public Response balance(@RestPath String customerId) {
		Customer customer = parseUuid(customerId).flatMap(Customer::ofPublicId)
				.orElseThrow(() -> new BusinessException(NOT_FOUND));
		Optional<PointsAccount> account = PointsAccount.ofCustomer(customer.id);
		return noStore(Response.ok(new Balance(customer.publicId, account.map(a -> a.balance).orElse(0L),
				account.map(a -> a.seq).orElse(0L))));
	}

	@POST
	@Path("/credits")
	@Transactional
	public Response credit(@HeaderParam(IDEMPOTENCY_KEY) String key, Posting body) {
		return post(Kind.CREDIT, key, body);
	}

	@POST
	@Path("/debits")
	@Transactional
	public Response debit(@HeaderParam(IDEMPOTENCY_KEY) String key, Posting body) {
		return post(Kind.DEBIT, key, body);
	}

	/** Reverses a credit or debit in full, once. */
	@POST
	@Path("/transactions/{transactionId}/refund")
	@Transactional
	public Response refund(@HeaderParam(IDEMPOTENCY_KEY) String key, @RestPath String transactionId, Refund body) {
		requireKey(key);
		String reference = body == null ? null : body.reference();
		if (reference != null && !REFERENCE.matcher(reference).matches()) {
			throw new BusinessException(INVALID, Map.of("reference", "invalid"));
		}
		UUID original = parseUuid(transactionId).orElseThrow(() -> new BusinessException(NOT_FOUND));
		return created(PointsTxn.refund(original, reference, key, Instant.now(), config.points().timeZone()));
	}

	@GET
	@Path("/events")
	public Response events(@RestQuery String after, @RestQuery Integer limit) {
		PointsEvent.Cursor cursor = after == null || after.isEmpty()
				? PointsEvent.Cursor.START
				: PointsEvent.Cursor.decode(after).orElseThrow(() -> new BusinessException(MALFORMED));
		int n = limit == null ? DEFAULT_EVENTS : Math.clamp(limit, 1, MAX_EVENTS);
		PointsEvent.Page page = PointsEvent.after(cursor, n);
		List<Event> events = page.items().stream()
				.map(i -> new Event(Long.toString(i.id()), i.type(), i.customerId(), i.at(),
						i.posted() == null ? null : Transaction.of(i.posted())))
				.toList();
		return noStore(Response.ok(new Events(events, page.next().encode())));
	}

	/** A business date's totals; final once the date has ended in {@code timeZone}. */
	@GET
	@Path("/reconciliation/{businessDate}")
	public Response reconciliation(@RestPath String businessDate) {
		LocalDate date;
		try {
			date = LocalDate.parse(businessDate);
		} catch (DateTimeParseException e) {
			throw new BusinessException(MALFORMED);
		}
		PointsTxn.Day day = PointsTxn.day(date);
		return noStore(Response.ok(new Reconciliation(date, config.points().timeZone().getId(),
				totals(day, Kind.CREDIT), totals(day, Kind.DEBIT), totals(day, Kind.REFUND), day.idsSha256())));
	}

	private Response post(Kind kind, String key, Posting body) {
		requireKey(key);
		if (body == null) throw new BusinessException(MALFORMED);
		Map<String, String> errors = new HashMap<>();
		Optional<UUID> customerId = Optional.empty();
		if (body.customerId() == null || body.customerId().isEmpty()) {
			errors.put("customerId", "required");
		} else {
			customerId = parseUuid(body.customerId());
			if (customerId.isEmpty()) errors.put("customerId", "invalid");
		}
		long points = 0;
		if (body.points() == null || body.points().isNull()) {
			errors.put("points", "required");
		} else if (!body.points().isIntegralNumber() || !body.points().canConvertToLong()
				|| body.points().longValue() < 1 || body.points().longValue() > MAX_POINTS) {
			errors.put("points", "invalid");
		} else {
			points = body.points().longValue();
		}
		if (body.reason() != null && !REASON.matcher(body.reason()).matches()) errors.put("reason", "invalid");
		if (body.reference() != null && !REFERENCE.matcher(body.reference()).matches()) errors.put("reference", "invalid");
		if (!errors.isEmpty()) throw new BusinessException(INVALID, errors);

		Customer customer = Customer.ofPublicId(customerId.get()).orElseThrow(() -> new BusinessException(NOT_FOUND));
		return created(PointsTxn.post(kind, customer, points, body.reason(), body.reference(), key, Instant.now(),
				config.points().timeZone()));
	}

	private static Response created(PointsTxn.Posted posted) {
		if (posted.replayed()) {
			logger.infof("points.replayed transactionId=%s kind=%s", posted.id(), posted.kind());
		} else {
			logger.infof("points.posted transactionId=%s kind=%s customerId=%s points=%d", posted.id(), posted.kind(),
					posted.customerId(), posted.points());
		}
		Response.ResponseBuilder r = Response.status(Response.Status.CREATED).entity(Transaction.of(posted));
		if (posted.replayed()) r.header("Idempotent-Replayed", "true");
		return noStore(r);
	}

	private static void requireKey(String key) {
		if (key == null || !KEY.matcher(key).matches()) throw new BusinessException(MALFORMED);
	}

	private static Totals totals(PointsTxn.Day day, Kind kind) {
		PointsTxn.Totals t = day.totals().get(kind);
		return new Totals(t.count(), t.points());
	}

	/** The standard 36-character form, in either case. {@link UUID#fromString} also takes short forms like {@code 1-2-3-4-5}. */
	private static Optional<UUID> parseUuid(String s) {
		if (s == null) return Optional.empty();
		try {
			UUID id = UUID.fromString(s);
			return id.toString().equals(s.toLowerCase(Locale.ROOT)) ? Optional.of(id) : Optional.empty();
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private static String lower(Kind kind) {
		return kind.name().toLowerCase(Locale.ROOT);
	}

	private static Response noStore(Response.ResponseBuilder r) {
		return r.header("Cache-Control", "no-store").build();
	}
}
