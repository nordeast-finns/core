package com.finnsbali;

import com.finnsbali.model.Staff;
import com.finnsbali.model.StaffEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.specification.RequestSpecification;

import java.util.List;

import static com.finnsbali.api.AdminApiFilter.ACTOR_SUB_HEADER;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;

/** Shared test data and request builders. Tests reset the tables before each test. */
public final class Fixtures {
	/** Matches {@code %test.finns.admin-api.token}. */
	public static final String TOKEN = "test-only-token-not-a-secret-012345678";

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

	public static void reset() {
		QuarkusTransaction.requiringNew().run(() -> {
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
}
