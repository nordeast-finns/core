package com.finns.trident.core;

import com.finns.trident.core.model.CheckIn;
import com.finns.trident.core.model.Qr;
import com.finns.trident.core.model.Staff;
import com.finns.trident.core.model.StaffEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.specification.RequestSpecification;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finns.trident.core.api.AdminApiFilter.ACTOR_SUB_HEADER;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;

/** Shared test data and request builders. Tests reset the tables before each test. */
public final class Fixtures {
	/** Matches {@code %test.finns.admin-api.token}. */
	public static final String TOKEN = "test-only-token-not-a-secret-012345678";

	/** Matches {@code %test.finns.gate-api.token}. */
	public static final String GATE_TOKEN = "test-only-gate-token-not-a-secret-0123";

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

	/** A request a gate device would make: authenticated, JSON. */
	public static RequestSpecification gate() {
		return given().header("Authorization", "Bearer " + GATE_TOKEN).contentType(JSON);
	}

	public static void reset() {
		QuarkusTransaction.requiringNew().run(() -> {
			CheckIn.deleteAll();
			Qr.deleteAll();
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

	/** Inserts a QR code directly and returns its QR text; {@code usedAt} null means unused. */
	public static String qr(Instant expiresAt, Instant usedAt) {
		byte[] token = new byte[32];
		new SecureRandom().nextBytes(token);
		QuarkusTransaction.requiringNew().run(() -> {
			Qr p = new Qr();
			p.id = UUID.randomUUID();
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

	public static List<Qr> qrs() {
		return QuarkusTransaction.requiringNew().call(() -> Qr.<Qr>listAll());
	}
}
