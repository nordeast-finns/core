package com.finns.trident.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.finns.trident.core.BusinessException;
import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsAccount;
import com.finns.trident.core.model.PointsTxn;
import com.finns.trident.core.model.PointsTxn.Kind;
import com.finns.trident.core.model.Staff;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.finns.trident.core.ErrorCode.FORBIDDEN;
import static com.finns.trident.core.ErrorCode.INVALID;
import static com.finns.trident.core.ErrorCode.MALFORMED;
import static com.finns.trident.core.ErrorCode.NOT_FOUND;

/**
 * Customers and their points, for the Admin Console. Any active staff member can read them and credit or
 * debit points. Customers are shown by {@link Customer#publicId}, the id points partners know them by:
 * Keycloak owns their profile, and core keeps no name or email.
 * <p>
 * Postings work like the Points API's, through {@link PointsTxn#postByStaff}: they need an
 * {@code Idempotency-Key}, scoped to the staff member, and record who made them.
 */
@Path(AdminApiFilter.PREFIX + "/customers")
public class CustomerApi {
	private static final Logger logger = Logger.getLogger(CustomerApi.class);

	static final int MAX_PAGE_SIZE = 200;

	/** How many of a customer's latest transactions the detail shows. */
	static final int HISTORY_LIMIT = 100;

	@Inject
	FinnsConfig config;

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

	/** {@code seq} is how many postings the customer has had; {@code transactions} are the latest, newest first. */
	@RegisterForReflection
	public record Detail(UUID customerId, long balance, long seq, Instant createdAt, List<Transaction> transactions) {
	}

	/**
	 * {@code points} is signed, from the customer's side, and {@code balance} is after it. {@code staffId}
	 * is null for the Points API's postings; {@code staffEmail} also once that staff member is deleted.
	 */
	@RegisterForReflection
	public record Transaction(UUID transactionId, String kind, long points, long balance, String reason,
			String reference, UUID refundOf, Instant recordedAt, Long staffId, String staffEmail) {
		static Transaction of(PointsTxn.Posted p, Long staffId, String staffEmail) {
			return new Transaction(p.id(), p.kind().name().toLowerCase(Locale.ROOT), p.points(), p.balance(),
					p.reason(), p.reference(), p.refundOf(), p.recordedAt(), staffId, staffEmail);
		}
	}

	/** {@code points} is a JSON node so a fraction or a numeric string is refused rather than coerced. */
	@RegisterForReflection
	public record Posting(JsonNode points, String reference) {
	}

	/** Newest first. */
	@GET
	public Response list(@RestQuery Integer page, @RestQuery Integer size) {
		requireStaff();
		int p = page == null ? 1 : Math.max(1, page);
		int n = size == null ? 50 : Math.clamp(size, 1, MAX_PAGE_SIZE);
		List<View> items = Customer.withBalances(p - 1, n).stream().map(View::of).toList();
		return PointsApi.noStore(Response.ok(new Page(items, Customer.count(), p, n)));
	}

	@GET
	@Path("{customerId}")
	public Response get(@RestPath String customerId) {
		requireStaff();
		Customer customer = find(customerId);
		Optional<PointsAccount> account = PointsAccount.ofCustomer(customer.id);
		List<Transaction> transactions = PointsTxn.history(customer, HISTORY_LIMIT).stream()
				.map(l -> Transaction.of(l.posted(), l.staffId(), l.staffEmail()))
				.toList();
		return PointsApi.noStore(Response.ok(new Detail(customer.publicId, account.map(a -> a.balance).orElse(0L),
				account.map(a -> a.seq).orElse(0L), customer.createdAt, transactions)));
	}

	@POST
	@Path("{customerId}/credits")
	@Transactional
	public Response credit(@RestPath String customerId, @HeaderParam(PointsApi.IDEMPOTENCY_KEY) String key,
			Posting body) {
		return post(Kind.CREDIT, customerId, key, body);
	}

	/** Fails with {@code insufficient_points} if the customer has fewer points. */
	@POST
	@Path("{customerId}/debits")
	@Transactional
	public Response debit(@RestPath String customerId, @HeaderParam(PointsApi.IDEMPOTENCY_KEY) String key,
			Posting body) {
		return post(Kind.DEBIT, customerId, key, body);
	}

	private Response post(Kind kind, String customerId, String key, Posting body) {
		Staff actor = requireStaff();
		PointsApi.requireKey(key);
		if (body == null) throw new BusinessException(MALFORMED);
		Customer customer = find(customerId);
		Map<String, String> errors = new HashMap<>();
		long points = PointsApi.points(body.points(), errors);
		PointsApi.checkReference(body.reference(), errors);
		if (!errors.isEmpty()) throw new BusinessException(INVALID, errors);

		PointsTxn.Posted posted = PointsTxn.postByStaff(kind, customer, points, body.reference(), actor, key,
				Instant.now(), config.points().timeZone());
		if (posted.replayed()) {
			logger.infof("points.replayed transactionId=%s kind=%s staffId=%d", posted.id(), posted.kind(), actor.id);
		} else {
			logger.infof("points.posted transactionId=%s kind=%s customerId=%s points=%d staffId=%d", posted.id(),
					posted.kind(), posted.customerId(), posted.points(), actor.id);
		}
		Response.ResponseBuilder r = Response.status(Response.Status.CREATED)
				.entity(Transaction.of(posted, actor.id, actor.email));
		if (posted.replayed()) r.header("Idempotent-Replayed", "true");
		return PointsApi.noStore(r);
	}

	private Staff requireStaff() {
		if (actorSub == null || actorSub.isBlank()) throw new BusinessException(FORBIDDEN);
		return Staff.findBySub(actorSub).filter(s -> s.active).orElseThrow(() -> new BusinessException(FORBIDDEN));
	}

	private static Customer find(String customerId) {
		return PointsApi.parseUuid(customerId).flatMap(Customer::ofPublicId)
				.orElseThrow(() -> new BusinessException(NOT_FOUND));
	}
}
