package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Staff;
import com.finns.trident.core.model.StaffEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static com.finns.trident.core.Fixtures.staff;
import static com.finns.trident.core.Fixtures.worker;
import static com.finns.trident.core.model.Staff.Role.ADMIN;
import static com.finns.trident.core.model.Staff.Role.STAFF;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class StaffAccessApiTest {
	private static final String PATH = "/api/v1/admin/staff-access";

	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	private static void signIn(String sub, String email, String status) {
		worker().body(Map.of("sub", sub, "email", email)).post(PATH + "/sign-ins")
				.then().statusCode(200).body("status", equalTo(status));
	}

	// --- lookup ---

	@Test
	void lookupAnswersActiveWithRole() {
		Staff s = staff("a@x.com", STAFF, true, "sub-a");
		worker().get(PATH + "/sub-a").then().statusCode(200)
				.body("status", equalTo("active"))
				.body("staffId", equalTo(s.id.intValue()))
				.body("role", equalTo("STAFF"));
	}

	@Test
	void lookupAnswersDisabledAndNone() {
		staff("a@x.com", ADMIN, false, "sub-a");
		worker().get(PATH + "/sub-a").then().statusCode(200)
				.body("status", equalTo("disabled")).body("role", nullValue());
		worker().get(PATH + "/unknown").then().statusCode(200).body("status", equalTo("none"));
	}

	@Test
	void lookupNeverMatchesByEmailOrWrites() {
		Staff s = staff("a@x.com", STAFF, true, null);
		worker().get(PATH + "/a@x.com").then().body("status", equalTo("none"));
		Staff after = Fixtures.reload(s.id);
		assertNull(after.jumpcloudSub);
		assertNull(after.lastSignInAt);
		assertEquals(s.version, after.version);
	}

	@Test
	void lookupRejectsOverlongSubject() {
		worker().get(PATH + "/" + "s".repeat(129)).then().statusCode(400).body("code", equalTo("malformed"));
	}

	// --- sign-in ---

	@Test
	void firstSignInLinksByEmailCaseInsensitively() {
		Staff s = staff("a@x.com", STAFF, true, null);
		signIn("sub-a", "  A@X.com ", "active");

		Staff after = Fixtures.reload(s.id);
		assertEquals("sub-a", after.jumpcloudSub);
		assertNotNull(after.lastSignInAt);
		assertEquals(s.version, after.version, "signing in must not make an open edit stale");
		List<StaffEvent> events = Fixtures.events(s.id);
		assertEquals(1, events.size());
		assertEquals(StaffEvent.Action.LINKED, events.getFirst().action);
		assertEquals(Staff.SYSTEM, events.getFirst().actorEmail);
	}

	@Test
	void activeSignInImpliesActiveLookup() {
		staff("a@x.com", ADMIN, true, null);
		signIn("sub-a", "a@x.com", "active");
		worker().get(PATH + "/sub-a").then().body("status", equalTo("active")).body("role", equalTo("ADMIN"));
	}

	@Test
	void linkedSubjectWinsOverChangedEmail() {
		staff("old@x.com", STAFF, true, "sub-a");
		signIn("sub-a", "new@x.com", "active");
	}

	@Test
	void emailLinkedToAnotherSubjectIsAMismatch() {
		Staff s = staff("a@x.com", ADMIN, true, "sub-old");
		signIn("sub-new", "a@x.com", "sub_mismatch");
		assertEquals("sub-old", Fixtures.reload(s.id).jumpcloudSub);
	}

	@Test
	void disabledRowIsNeitherLinkedNorSignedIn() {
		Staff s = staff("a@x.com", STAFF, false, null);
		signIn("sub-a", "a@x.com", "disabled");
		Staff after = Fixtures.reload(s.id);
		assertNull(after.jumpcloudSub);
		assertNull(after.lastSignInAt);
	}

	@Test
	void unknownOrMissingEmailIsNone() {
		staff("a@x.com", STAFF, true, null);
		signIn("sub-a", "b@x.com", "none");
		worker().body(Map.of("sub", "sub-a")).post(PATH + "/sign-ins").then().body("status", equalTo("none"));
	}

	@Test
	void signInRejectsMissingSubject() {
		worker().body(Map.of("email", "a@x.com")).post(PATH + "/sign-ins").then().statusCode(400)
				.body("code", equalTo("malformed"));
		worker().body("{").post(PATH + "/sign-ins").then().statusCode(400);
	}

	@Test
	void concurrentFirstSignInsLinkOnce() throws Exception {
		Staff s = staff("a@x.com", STAFF, true, null);
		CountDownLatch start = new CountDownLatch(1);
		Runnable signIn = () -> {
			try {
				start.await();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			signIn("sub-a", "a@x.com", "active");
		};
		CompletableFuture<Void> first = CompletableFuture.runAsync(signIn);
		CompletableFuture<Void> second = CompletableFuture.runAsync(signIn);
		start.countDown();
		CompletableFuture.allOf(first, second).get();

		assertEquals("sub-a", Fixtures.reload(s.id).jumpcloudSub);
		assertEquals(1, Fixtures.events(s.id).size());
	}
}
