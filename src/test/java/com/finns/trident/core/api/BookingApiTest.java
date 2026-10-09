package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.Handoff;
import com.finns.trident.core.model.PointsTxn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.finns.trident.core.Fixtures.CUSTOMER_SUB;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class BookingApiTest {
	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	private static String mint() {
		String token = Fixtures.customerToken(CUSTOMER_SUB, c -> c.claim("sid", "kc-session-1")
				.claim("name", "Dewi Lestari").claim("email", "dewi@example.com"));
		return given().auth().oauth2(token).post("/api/v1/app/handoffs").then().statusCode(201).extract().path("code");
	}

	private static io.restassured.response.ValidatableResponse redeem(String code) {
		return Fixtures.booking().body("{\"code\":\"" + code + "\"}").post("/api/v1/booking/handoffs/redeem").then();
	}

	@Test
	void redeemsACodeOnceAndClearsWhatItCarried() {
		String code = mint();
		redeem(code).statusCode(200).header("Cache-Control", "no-store")
				.body("sub", equalTo(CUSTOMER_SUB))
				.body("sid", equalTo("kc-session-1"))
				.body("displayName", equalTo("Dewi Lestari"))
				.body("email", equalTo("dewi@example.com"));

		Handoff row = Fixtures.handoffs().getFirst();
		assertNotNull(row.usedAt);
		assertNull(row.displayName);
		assertNull(row.email);
		// A replay is indistinguishable from an unknown code.
		redeem(code).statusCode(404).body("code", equalTo("not_found"));
	}

	@Test
	void peekDoesNotUseTheCode() {
		String code = mint();
		for (int i = 0; i < 2; i++) {
			Fixtures.booking().body("{\"code\":\"" + code + "\"}").post("/api/v1/booking/handoffs/peek").then()
					.statusCode(200).header("Cache-Control", "no-store").body("displayName", equalTo("Dewi Lestari"))
					.body("email", equalTo("dewi@example.com"));
		}
		redeem(code).statusCode(200);
		Fixtures.booking().body("{\"code\":\"" + code + "\"}").post("/api/v1/booking/handoffs/peek").then().statusCode(404);
	}

	@Test
	void purgeClearsWhatExpiredCodesCarriedAndDeletesOldOnes() {
		for (int i = 0; i < 3; i++) mint();
		QuarkusTransaction.requiringNew().run(() -> {
			List<Handoff> rows = Handoff.<Handoff>listAll().stream().sorted(java.util.Comparator.comparing(h -> h.id)).toList();
			// One just expired, one expired long ago; one still usable.
			rows.get(0).expiresAt = Instant.now().minusSeconds(1);
			rows.get(1).expiresAt = Instant.now().minus(java.time.Duration.ofHours(2));
		});
		new com.finns.trident.core.HandoffPurge().run();

		List<Handoff> rows = Fixtures.handoffs();
		assertEquals(2, rows.size());
		Handoff expired = rows.stream().filter(h -> h.expiresAt.isBefore(Instant.now())).findFirst().orElseThrow();
		assertNull(expired.displayName);
		assertNull(expired.email);
		Handoff usable = rows.stream().filter(h -> h.expiresAt.isAfter(Instant.now())).findFirst().orElseThrow();
		assertEquals("Dewi Lestari", usable.displayName);
		assertEquals("dewi@example.com", usable.email);
	}

	@Test
	void refusesAnExpiredCode() {
		String code = mint();
		QuarkusTransaction.requiringNew().run(() -> Handoff.update("expiresAt = ?1", Instant.now().minusSeconds(1)));
		redeem(code).statusCode(404);
		Fixtures.booking().body("{\"code\":\"" + code + "\"}").post("/api/v1/booking/handoffs/peek").then().statusCode(404);
		assertNull(Fixtures.handoffs().getFirst().usedAt);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "x", "not-a-code", "FINNS1:abcdefghijklmnopqrstuvwxyzabcdefghijklmnopq", "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"})
	void refusesTextThatIsNotACode(String code) {
		Fixtures.booking().body(java.util.Map.of("code", code == null ? "" : code)).post("/api/v1/booking/handoffs/redeem").then().statusCode(404);
		Fixtures.booking().body("{}").post("/api/v1/booking/handoffs/redeem").then().statusCode(404);
		Fixtures.booking().post("/api/v1/booking/handoffs/redeem").then().statusCode(404);
	}

	@Test
	void onlyOneOfManyConcurrentRedeemsWins() throws Exception {
		String code = mint();
		var pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Integer>> calls = IntStream.range(0, 8)
					.<Callable<Integer>>mapToObj(i -> () -> redeem(code).extract().statusCode()).toList();
			List<Future<Integer>> results = pool.invokeAll(calls);
			long wins = 0;
			for (Future<Integer> f : results) if (f.get() == 200) wins++;
			assertEquals(1, wins);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void protectsEveryBookingPathWithTheBookingToken() {
		given().contentType(JSON).body("{\"code\":\"x\"}").post("/api/v1/booking/handoffs/redeem").then().statusCode(401).body(emptyString());
		given().header("Authorization", "Bearer " + Fixtures.BOOKING_TOKEN.replace('t', 'x')).contentType(JSON)
				.body("{\"code\":\"x\"}").post("/api/v1/booking/handoffs/redeem").then().statusCode(401);
		// The other APIs' tokens open nothing here, and this token opens nothing there.
		Fixtures.worker().body("{\"code\":\"x\"}").post("/api/v1/booking/handoffs/redeem").then().statusCode(401);
		Fixtures.gate().body("{\"code\":\"x\"}").post("/api/v1/booking/handoffs/redeem").then().statusCode(401);
		Fixtures.booking().get("/api/v1/admin/staff-access/x").then().statusCode(401);
		Fixtures.booking().body("{\"gateId\":\"g\",\"qr\":\"x\"}").post("/api/v1/gate/check-ins").then().statusCode(401);
		// Unknown paths too, so callers can't probe which endpoints exist.
		given().get("/api/v1/booking/nothing-here").then().statusCode(401);
		given().get("/api/v1/booking").then().statusCode(401);
	}

	@Test
	void unauthenticatedRequestsNeverReachTheDatabase() {
		String code = mint();
		given().contentType(JSON).body("{\"code\":\"" + code + "\"}").post("/api/v1/booking/handoffs/redeem").then().statusCode(401);
		assertNull(Fixtures.handoffs().getFirst().usedAt);
		given().get("/api/v1/booking/handoffs/redeem").then().statusCode(401).header("Set-Cookie", nullValue());
	}

	// --- paid bookings ---

	private static io.restassured.response.ValidatableResponse booked(Object totalIdr, String bookingId) {
		Map<String, Object> body = new HashMap<>();
		body.put("sub", CUSTOMER_SUB);
		body.put("bookingId", bookingId);
		body.put("totalIdr", totalIdr);
		return Fixtures.booking().body(body).post("/api/v1/booking/bookings").then();
	}

	@Test
	void aPaidBookingEarnsAPointPerTenThousandRupiahOnce() {
		booked(3_155_000, "b-1").statusCode(204).header("Cache-Control", "no-store");
		// booking never retries today, but a repeat must not credit twice.
		booked(3_155_000, "b-1").statusCode(204);

		Customer customer = Fixtures.findCustomer(CUSTOMER_SUB);
		assertEquals(315, Fixtures.balance(customer.id));
		PointsTxn txn = Fixtures.pointsTxns().getFirst();
		assertEquals(PointsTxn.Client.CORE, txn.client);
		assertEquals(PointsTxn.BOOKING_REASON, txn.reason);
		assertEquals("b-1", txn.reference);

		booked(25_000, "b-2").statusCode(204);
		assertEquals(317, Fixtures.balance(customer.id));
	}

	@Test
	void aBookingUnderAPointEarnsNothing() {
		booked(9_999, "b-1").statusCode(204);
		booked(0, "b-2").statusCode(204);
		assertEquals(0, Fixtures.pointsTxns().size());
		// The customer exists, as their first request would make them.
		assertEquals(1, Fixtures.customers().size());
	}

	@ParameterizedTest
	@ValueSource(strings = {"3.5", "\"3155000\"", "-1", "true", "{}", "99999999999999999999",
			// 1,000,000,001 points: more than one posting can hold.
			"10000000010000"})
	void refusesATotalThatIsNotAWholeRupiahAmount(String totalIdr) {
		Fixtures.booking().body("{\"sub\":\"" + CUSTOMER_SUB + "\",\"bookingId\":\"b-1\",\"totalIdr\":" + totalIdr + "}")
				.post("/api/v1/booking/bookings").then()
				.statusCode(422).body("code", equalTo("invalid")).body("fields.totalIdr", equalTo("invalid"));
		assertEquals(0, Fixtures.customers().size());
	}

	@Test
	void refusesABookingWithoutItsFields() {
		Fixtures.booking().body("{}").post("/api/v1/booking/bookings").then().statusCode(422)
				.body("fields.sub", equalTo("required"))
				.body("fields.bookingId", equalTo("required"))
				.body("fields.totalIdr", equalTo("required"));
		Fixtures.booking().body(Map.of("sub", "has space", "bookingId", "b/1", "totalIdr", 1)).post("/api/v1/booking/bookings")
				.then().statusCode(422)
				.body("fields.sub", equalTo("invalid"))
				.body("fields.bookingId", equalTo("invalid"));
		Fixtures.booking().body(Map.of("sub", CUSTOMER_SUB, "bookingId", "b".repeat(65), "totalIdr", 1))
				.post("/api/v1/booking/bookings").then().statusCode(422).body("fields.bookingId", equalTo("invalid"));
		Fixtures.booking().post("/api/v1/booking/bookings").then().statusCode(400);
		assertEquals(0, Fixtures.customers().size());
	}

	@Test
	void onlyBookingCanReportABooking() {
		String body = "{\"sub\":\"" + CUSTOMER_SUB + "\",\"bookingId\":\"b-1\",\"totalIdr\":3155000}";
		given().contentType(JSON).body(body).post("/api/v1/booking/bookings").then().statusCode(401);
		Fixtures.gate().body(body).post("/api/v1/booking/bookings").then().statusCode(401);
		Fixtures.points().body(body).post("/api/v1/booking/bookings").then().statusCode(401);
		assertEquals(0, Fixtures.customers().size());
	}
}
