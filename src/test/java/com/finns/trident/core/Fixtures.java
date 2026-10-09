package com.finns.trident.core;

import com.finns.trident.core.model.CheckIn;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.Handoff;
import com.finns.trident.core.model.PointsAccount;
import com.finns.trident.core.model.Qr;
import com.finns.trident.core.model.Staff;
import com.finns.trident.core.model.StaffEvent;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.specification.RequestSpecification;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;
import io.smallrye.jwt.util.KeyUtils;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.finns.trident.core.api.AdminApiFilter.ACTOR_SUB_HEADER;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;

/** Shared test data and request builders. Tests reset the tables before each test. */
public final class Fixtures {
	/** Matches {@code %test.finns.admin-api.token}. */
	public static final String TOKEN = "test-only-token-not-a-secret-012345678";

	/** Matches {@code %test.finns.gate-api.token}. */
	public static final String GATE_TOKEN = "test-only-gate-token-not-a-secret-0123";

	/** Matches {@code %test.finns.booking.api-token}. */
	public static final String BOOKING_TOKEN = "test-only-booking-token-not-a-secret-0";

	/** Matches {@code %test.finns.points.api-token}. */
	public static final String POINTS_TOKEN = "test-only-points-token-not-a-secret-0";

	/** Matches {@code %test.quarkus.oidc.token.issuer}. */
	public static final String ISSUER = "https://auth.test/realms/finns";

	/** A Keycloak subject, as the customer app's tokens carry it. */
	public static final String CUSTOMER_SUB = "6c1f7a52-8a77-4a43-9f43-6f5e0b6f2c11";

	private Fixtures() {
	}

	/** A request the Worker would make: authenticated, JSON. */
	public static RequestSpecification worker() {
		return given().header("Authorization", "Bearer " + TOKEN).contentType(JSON);
	}

	/** A request the Worker would make on behalf of the staff member linked to {@code sub}. */
	public static RequestSpecification as(String sub) {
		return worker().header(ACTOR_SUB_HEADER, sub);
	}

	/** A request the customer app would make for the customer with Keycloak subject {@code sub}. */
	public static RequestSpecification customer(String sub) {
		return given().auth().oauth2(customerToken(sub, UnaryOperator.identity())).contentType(JSON);
	}

	/**
	 * An access token as Keycloak issues it to the customer app, signed with the test key.
	 * {@code tweak} changes it, for tests of tokens core must refuse.
	 */
	public static String customerToken(String sub, UnaryOperator<JwtClaimsBuilder> tweak) {
		JwtClaimsBuilder claims = Jwt.issuer(ISSUER)
				.subject(sub)
				.audience("account")
				.claim("azp", "trident-app")
				.claim("typ", "Bearer")
				.expiresIn(300);
		return tweak.apply(claims).sign(signingKey());
	}

	private static PrivateKey signingKey() {
		try {
			return KeyUtils.readPrivateKey("/customer-token-key.pem");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** A request the booking website's server would make: authenticated, JSON. */
	public static RequestSpecification booking() {
		return given().header("Authorization", "Bearer " + BOOKING_TOKEN).contentType(JSON);
	}

	/** A request a points partner (Sota) would make: authenticated, JSON. */
	public static RequestSpecification points() {
		return given().header("Authorization", "Bearer " + POINTS_TOKEN).contentType(JSON);
	}

	/** A points partner's posting request with {@code key} as its Idempotency-Key. */
	public static RequestSpecification points(String key) {
		return points().header("Idempotency-Key", key);
	}

	/** A request a gate device would make: authenticated, JSON. */
	public static RequestSpecification gate() {
		return given().header("Authorization", "Bearer " + GATE_TOKEN).contentType(JSON);
	}

	public static void reset() {
		QuarkusTransaction.requiringNew().run(() -> {
			// The ledger is append-only, which truncate bypasses.
			Panache.getEntityManager().createNativeQuery("truncate points_entry, points_txn, points_event").executeUpdate();
			PointsAccount.delete("customerId is not null");
			CheckIn.deleteAll();
			Handoff.deleteAll();
			Qr.deleteAll();
			Customer.deleteAll();
			StaffEvent.deleteAll();
			Staff.deleteAll();
		});
	}

	/** Inserts a row directly; {@code sub} null means not linked yet. */
	public static Staff staff(String email, Staff.Role role, boolean active, String sub) {
		return QuarkusTransaction.requiringNew().call(() -> {
			Staff s = new Staff();
			s.email = email;
			s.role = role;
			s.active = active;
			s.jumpcloudSub = sub;
			s.createdBy = Staff.SYSTEM;
			s.updatedBy = Staff.SYSTEM;
			s.persist();
			return s;
		});
	}

	public static Staff reload(long id) {
		return QuarkusTransaction.requiringNew().call(() -> Staff.<Staff>findById(id));
	}

	public static List<StaffEvent> events(long staffId) {
		return QuarkusTransaction.requiringNew().call(() -> StaffEvent.latestFor(staffId, 100));
	}

	/** Inserts a customer directly, without a display name or email. */
	public static Customer customerRow(String sub) {
		return customerRow(sub, null, null);
	}

	/** Inserts a customer directly, as their first request with these claims would. */
	public static Customer customerRow(String sub, String displayName, String email) {
		return QuarkusTransaction.requiringNew()
				.call(() -> Customer.ofSubject(sub, new Customer.Profile(displayName, email), Instant.now()));
	}

	/**
	 * Inserts a QR code issued to the customer {@code customerId} directly and returns its QR text;
	 * {@code usedAt} null means unused.
	 */
	public static String qr(long customerId, Instant expiresAt, Instant usedAt) {
		byte[] token = new byte[32];
		new SecureRandom().nextBytes(token);
		QuarkusTransaction.requiringNew().run(() -> {
			Qr p = new Qr();
			p.id = UUID.randomUUID();
			p.customerId = customerId;
			p.tokenHash = Hashes.sha256(token);
			p.issuedAt = expiresAt.minusSeconds(60);
			p.expiresAt = expiresAt;
			p.usedAt = usedAt;
			p.usedGate = usedAt == null ? null : "earlier-gate";
			p.persist();
		});
		return Qr.encode(token);
	}

	public static List<CheckIn> checkIns() {
		return QuarkusTransaction.requiringNew().call(() -> CheckIn.<CheckIn>listAll());
	}

	public static List<Handoff> handoffs() {
		return QuarkusTransaction.requiringNew().call(() -> Handoff.<Handoff>listAll());
	}

	public static List<Qr> qrs() {
		return QuarkusTransaction.requiringNew().call(() -> Qr.<Qr>listAll());
	}

	public static List<Customer> customers() {
		return QuarkusTransaction.requiringNew().call(() -> Customer.<Customer>listAll());
	}
}
