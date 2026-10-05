package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Handoff;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
					.statusCode(200).header("Cache-Control", "no-store").body("displayName", equalTo("Dewi Lestari"));
		}
		redeem(code).statusCode(200);
		Fixtures.booking().body("{\"code\":\"" + code + "\"}").post("/api/v1/booking/handoffs/peek").then().statusCode(404);
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
}
