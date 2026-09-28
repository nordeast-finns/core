package com.finnsbali.api;

import com.finnsbali.Fixtures;
import com.finnsbali.model.Staff;
import com.finnsbali.model.StaffEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static com.finnsbali.Fixtures.as;
import static com.finnsbali.Fixtures.staff;
import static com.finnsbali.Fixtures.worker;
import static com.finnsbali.model.Staff.Role.ADMIN;
import static com.finnsbali.model.Staff.Role.STAFF;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StaffApiTest {
	private static final String PATH = "/api/v1/admin/staff";

	private static final String ADMIN_SUB = "sub-admin";

	private Staff admin;

	@BeforeEach
	void reset() {
		Fixtures.reset();
		admin = staff("admin@bla.com", ADMIN, true, ADMIN_SUB);
	}

	private static Map<String, Object> input(String email, String role, boolean active) {
		Map<String, Object> m = new HashMap<>();
		m.put("email", email);
		m.put("displayName", "Name of " + email);
		m.put("role", role);
		m.put("active", active);
		return m;
	}

	private static String etag(Staff s) {
		return "\"" + Fixtures.reload(s.id).version + "\"";
	}

	// --- actor ---

	@Test
	void requiresAnActiveAdminActor() {
		staff("staff@bla.com", STAFF, true, "sub-staff");
		staff("off@bla.com", ADMIN, false, "sub-off");
		worker().get(PATH).then().statusCode(403).body("code", equalTo("forbidden"));
		as("sub-staff").get(PATH).then().statusCode(403);
		as("sub-off").get(PATH).then().statusCode(403);
		as("sub-unknown").get(PATH).then().statusCode(403);
		as(ADMIN_SUB).get(PATH).then().statusCode(200);
	}

	@Test
	void problemDetailsUseProblemJson() {
		worker().get(PATH).then().contentType("application/problem+json")
				.body("status", equalTo(403)).body("title", equalTo("Forbidden")).body("type", equalTo("about:blank"));
	}

	// --- create ---

	@Test
	void createsWithEtagLocationAndEvent() {
		Response r = as(ADMIN_SUB).body(input(" Staff@Bla.com ", "STAFF", true)).post(PATH);
		r.then().statusCode(201)
				.header("ETag", "\"0\"")
				.header("Location", containsString(PATH + "/"))
				.body("email", equalTo("staff@bla.com"))
				.body("role", equalTo("STAFF"))
				.body("linked", equalTo(false))
				.body("createdBy", equalTo("admin@bla.com"))
				.body("createdAt", notNullValue());
		long id = r.jsonPath().getLong("id");
		List<StaffEvent> events = Fixtures.events(id);
		assertEquals(StaffEvent.Action.CREATED, events.getFirst().action);
		assertEquals(admin.id, events.getFirst().actorId);
	}

	@Test
	void createReportsFieldErrors() {
		Map<String, Object> bad = new HashMap<>();
		bad.put("email", "bü@bla.com");
		bad.put("displayName", "x".repeat(201));
		bad.put("role", "OWNER");
		as(ADMIN_SUB).body(bad).post(PATH).then().statusCode(422)
				.body("code", equalTo("invalid"))
				.body("fields.email", equalTo("invalid_email"))
				.body("fields.displayName", equalTo("too_long"))
				.body("fields.role", equalTo("invalid"))
				.body("fields.active", equalTo("required"));
		as(ADMIN_SUB).body(input("", "STAFF", true)).post(PATH).then().body("fields.email", equalTo("required"));
		as(ADMIN_SUB).body(input("a".repeat(250) + "@b.co", "STAFF", true)).post(PATH).then()
				.body("fields.email", equalTo("too_long"));
		as(ADMIN_SUB).body(input("no-at-sign", "STAFF", true)).post(PATH).then()
				.body("fields.email", equalTo("invalid_email"));
	}

	@Test
	void createRejectsDuplicateEmailIgnoringCase() {
		as(ADMIN_SUB).body(input("ADMIN@bla.com", "STAFF", true)).post(PATH).then().statusCode(409)
				.body("code", equalTo("email_taken"));
	}

	@Test
	void rejectsMalformedBodies() {
		as(ADMIN_SUB).body("{").post(PATH).then().statusCode(400).body("code", equalTo("malformed"));
		as(ADMIN_SUB).body("{\"email\": 1, \"active\": \"maybe\"}").post(PATH).then().statusCode(400);
	}

	// --- read ---

	@Test
	void getReturnsEtagAnd404() {
		as(ADMIN_SUB).get(PATH + "/" + admin.id).then().statusCode(200).header("ETag", "\"0\"")
				.body("linked", equalTo(true));
		as(ADMIN_SUB).get(PATH + "/999999").then().statusCode(404).body("code", equalTo("not_found"));
		as(ADMIN_SUB).get(PATH + "/abc").then().statusCode(404);
	}

	@Test
	void listFiltersPagesAndSortsByEmail() {
		staff("b@x.com", STAFF, true, null);
		staff("c@x.com", STAFF, false, null);
		as(ADMIN_SUB).get(PATH).then().statusCode(200)
				.body("total", equalTo(3)).body("page", equalTo(1)).body("size", equalTo(50))
				.body("items.email", equalTo(List.of("admin@bla.com", "b@x.com", "c@x.com")));
		as(ADMIN_SUB).queryParam("role", "STAFF").queryParam("active", "false").get(PATH).then()
				.body("items.email", equalTo(List.of("c@x.com")));
		as(ADMIN_SUB).queryParam("q", "X.COM").queryParam("size", 1).queryParam("page", 2).get(PATH).then()
				.body("total", equalTo(2)).body("items.email", equalTo(List.of("c@x.com")));
		as(ADMIN_SUB).queryParam("role", "OWNER").get(PATH).then().statusCode(400);
		as(ADMIN_SUB).queryParam("size", 10_000).get(PATH).then().body("size", equalTo(StaffApi.MAX_PAGE_SIZE));
	}

	@Test
	void searchTreatsWildcardsLiterally() {
		staff("a_b@x.com", STAFF, true, null);
		staff("axb@x.com", STAFF, true, null);
		as(ADMIN_SUB).queryParam("q", "a_b").get(PATH).then().body("items.email", equalTo(List.of("a_b@x.com")));
		as(ADMIN_SUB).queryParam("q", "%").get(PATH).then().body("items", hasSize(0));
	}

	// --- update ---

	@Test
	void updateNeedsCurrentVersion() {
		Staff s = staff("s@x.com", STAFF, true, null);
		as(ADMIN_SUB).body(input("s@x.com", "ADMIN", true)).put(PATH + "/" + s.id).then().statusCode(428)
				.body("code", equalTo("precondition_required"));
		as(ADMIN_SUB).header("If-Match", "\"7\"").body(input("s@x.com", "ADMIN", true)).put(PATH + "/" + s.id)
				.then().statusCode(412).body("code", equalTo("stale"));
	}

	@Test
	void updateChangesFieldsBumpsVersionAndRecordsDiff() {
		Staff s = staff("s@x.com", STAFF, true, null);
		as(ADMIN_SUB).header("If-Match", etag(s)).body(input("s2@x.com", "ADMIN", false)).put(PATH + "/" + s.id)
				.then().statusCode(200).header("ETag", "\"1\"")
				.body("email", equalTo("s2@x.com")).body("role", equalTo("ADMIN")).body("active", equalTo(false))
				.body("updatedBy", equalTo("admin@bla.com"));

		StaffEvent event = Fixtures.events(s.id).getFirst();
		assertEquals(StaffEvent.Action.UPDATED, event.action);
		assertEquals(List.of("STAFF", "ADMIN"), event.changes.get("role"));
		assertEquals(List.of(true, false), event.changes.get("active"));
		assertEquals(List.of("s@x.com", "s2@x.com"), event.changes.get("email"));
	}

	@Test
	void noOpUpdateKeepsVersionAndWritesNoEvent() {
		Staff s = staff("s@x.com", STAFF, true, null);
		Map<String, Object> same = input("s@x.com", "STAFF", true);
		same.put("displayName", null);
		as(ADMIN_SUB).header("If-Match", etag(s)).body(same).put(PATH + "/" + s.id)
				.then().statusCode(200).header("ETag", "\"0\"");
		assertTrue(Fixtures.events(s.id).isEmpty());
	}

	@Test
	void updateRejectsEmailOfAnotherStaffMember() {
		Staff s = staff("s@x.com", STAFF, true, null);
		as(ADMIN_SUB).header("If-Match", etag(s)).body(input("admin@bla.com", "STAFF", true))
				.put(PATH + "/" + s.id).then().statusCode(409).body("code", equalTo("email_taken"));
	}

	@Test
	void nobodyChangesTheirOwnAccess() {
		String self = PATH + "/" + admin.id;
		as(ADMIN_SUB).header("If-Match", etag(admin)).body(input("admin@bla.com", "STAFF", true)).put(self)
				.then().statusCode(409).body("code", equalTo("self_change"));
		as(ADMIN_SUB).header("If-Match", etag(admin)).body(input("admin@bla.com", "ADMIN", false)).put(self)
				.then().statusCode(409);
		as(ADMIN_SUB).header("If-Match", etag(admin)).delete(self + "/link").then().statusCode(409);
		as(ADMIN_SUB).header("If-Match", etag(admin)).delete(self).then().statusCode(409);

		// Name and email are not access.
		as(ADMIN_SUB).header("If-Match", etag(admin)).body(input("me@bla.com", "ADMIN", true)).put(self)
				.then().statusCode(200).body("email", equalTo("me@bla.com"));
	}

	@Test
	void editKeepsALinkMadeAfterTheFormWasLoaded() {
		Staff s = staff("s@x.com", STAFF, true, null);
		String version = etag(s);
		worker().body(Map.of("sub", "sub-s", "email", "s@x.com")).post("/api/v1/admin/staff-access/sign-ins")
				.then().body("status", equalTo("active"));

		as(ADMIN_SUB).header("If-Match", version).body(input("s@x.com", "ADMIN", true)).put(PATH + "/" + s.id)
				.then().statusCode(200).body("linked", equalTo(true));
		assertEquals("sub-s", Fixtures.reload(s.id).jumpcloudSub);
	}

	@Test
	void adminsDemotingEachOtherAtOnceLeaveOneAdmin() throws Exception {
		Staff other = staff("other@bla.com", ADMIN, true, "sub-other");
		CountDownLatch start = new CountDownLatch(1);
		CompletableFuture<Integer> a = demoteAsync(start, ADMIN_SUB, other);
		CompletableFuture<Integer> b = demoteAsync(start, "sub-other", admin);
		start.countDown();

		List<Integer> statuses = List.of(a.get(), b.get());
		assertTrue(statuses.contains(200), statuses.toString());
		assertTrue(statuses.contains(403), statuses.toString());
		long admins = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(Staff::countActiveAdmins);
		assertEquals(1, admins);
	}

	private CompletableFuture<Integer> demoteAsync(CountDownLatch start, String actorSub, Staff target) {
		String version = etag(target);
		return CompletableFuture.supplyAsync(() -> {
			try {
				start.await();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return as(actorSub).header("If-Match", version).body(input(target.email, "STAFF", true))
					.put(PATH + "/" + target.id).statusCode();
		});
	}

	// --- unlink / delete / events ---

	@Test
	void unlinkClearsTheSubjectSoTheNextSignInRelinks() {
		Staff s = staff("s@x.com", STAFF, true, "sub-old");
		as(ADMIN_SUB).header("If-Match", etag(s)).delete(PATH + "/" + s.id + "/link").then().statusCode(200)
				.body("linked", equalTo(false)).header("ETag", "\"1\"");
		assertNull(Fixtures.reload(s.id).jumpcloudSub);

		worker().body(Map.of("sub", "sub-new", "email", "s@x.com")).post("/api/v1/admin/staff-access/sign-ins")
				.then().body("status", equalTo("active"));
		List<StaffEvent.Action> actions = Fixtures.events(s.id).stream().map(e -> e.action).toList();
		assertEquals(List.of(StaffEvent.Action.LINKED, StaffEvent.Action.UNLINKED), actions);
	}

	@Test
	void deleteRemovesTheRowButKeepsHistory() {
		Staff s = staff("s@x.com", STAFF, true, null);
		as(ADMIN_SUB).header("If-Match", "\"9\"").delete(PATH + "/" + s.id).then().statusCode(412);
		as(ADMIN_SUB).header("If-Match", etag(s)).delete(PATH + "/" + s.id).then().statusCode(204);
		assertNull(Fixtures.reload(s.id));
		assertEquals(StaffEvent.Action.DELETED, Fixtures.events(s.id).getFirst().action);
		assertFalse(Fixtures.events(s.id).getFirst().changes.isEmpty());
		as(ADMIN_SUB).header("If-Match", "\"0\"").delete(PATH + "/" + s.id).then().statusCode(404);
	}

	@Test
	void eventsAreNewestFirst() {
		Staff s = staff("s@x.com", STAFF, true, null);
		as(ADMIN_SUB).header("If-Match", etag(s)).body(input("s@x.com", "ADMIN", true)).put(PATH + "/" + s.id);
		as(ADMIN_SUB).header("If-Match", etag(s)).body(input("s@x.com", "STAFF", true)).put(PATH + "/" + s.id);
		as(ADMIN_SUB).get(PATH + "/" + s.id + "/events").then().statusCode(200)
				.body("action", equalTo(List.of("UPDATED", "UPDATED")))
				.body("[0].changes.role", equalTo(List.of("ADMIN", "STAFF")))
				.body("[0].actorEmail", equalTo("admin@bla.com"));
		as(ADMIN_SUB).get(PATH + "/999999/events").then().statusCode(404);
	}

	@Test
	void escapeLikeEscapesTheEscapeCharacterToo() {
		assertEquals("a!!b!%c!_", StaffApi.escapeLike("a!b%c_"));
	}
}
