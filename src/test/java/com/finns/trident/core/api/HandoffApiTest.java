package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Handoff;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.finns.trident.core.Fixtures.CUSTOMER_SUB;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class HandoffApiTest {
	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	@Test
	void issuesAOneTimeCodeToTheCustomer() {
		Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/handoffs").then().statusCode(201)
				.header("Cache-Control", "no-store")
				.body("code", matchesPattern("[A-Za-z0-9_-]{43}"))
				.body("ttlSeconds", equalTo(60));

		List<Handoff> rows = Fixtures.handoffs();
		assertEquals(1, rows.size());
		Handoff row = rows.getFirst();
		assertEquals(Fixtures.customers().getFirst().id, row.customerId);
		assertNull(row.usedAt);
		assertEquals(Duration.ofSeconds(60), Duration.between(row.issuedAt, row.expiresAt));
		// Only the hash is stored.
		assertEquals(32, row.codeHash.length);
	}

	@Test
	void carriesTheTokensIdentityClaims() {
		String token = Fixtures.customerToken(CUSTOMER_SUB, c -> c.claim("sid", "kc-session-1")
				.claim("name", "  Dewi Lestari ").claim("email", "dewi@example.com"));
		given().auth().oauth2(token).post("/api/v1/app/handoffs").then().statusCode(201);

		Handoff row = Fixtures.handoffs().getFirst();
		assertEquals("kc-session-1", row.keycloakSid);
		assertEquals("Dewi Lestari", row.displayName);
		assertEquals("dewi@example.com", row.email);
		// The customer's own copy comes from Keycloak only, never from a request.
		assertNull(Fixtures.findCustomer(CUSTOMER_SUB).displayName);
		assertNull(Fixtures.findCustomer(CUSTOMER_SUB).email);
	}

	@Test
	void cutsAnOverlongNameAndDropsAnOverlongEmail() {
		// 😀 is two UTF-16 chars but one character to Postgres; a cut never splits it.
		String token = Fixtures.customerToken(CUSTOMER_SUB,
				c -> c.claim("name", "😀".repeat(200)).claim("email", "y".repeat(400) + "@example.com"));
		given().auth().oauth2(token).post("/api/v1/app/handoffs").then().statusCode(201);
		Handoff row = Fixtures.handoffs().getFirst();
		assertEquals("😀".repeat(128), row.displayName);
		assertNull(row.email);
	}

	@Test
	void dropsControlCharactersSoTheInsertCantFail() {
		String token = Fixtures.customerToken(CUSTOMER_SUB,
				c -> c.claim("name", "De\0wi\nLestari").claim("email", "\0\t").claim("sid", "kc\0-1"));
		given().auth().oauth2(token).post("/api/v1/app/handoffs").then().statusCode(201);
		Handoff row = Fixtures.handoffs().getFirst();
		assertEquals("DewiLestari", row.displayName);
		assertNull(row.email);
		assertEquals("kc-1", row.keycloakSid);
	}

	@Test
	void fallsBackToThePreferredUsernameAndKeepsClaimsBounded() {
		String token = Fixtures.customerToken(CUSTOMER_SUB, c -> c.claim("preferred_username", "x".repeat(500)));
		given().auth().oauth2(token).post("/api/v1/app/handoffs").then().statusCode(201);
		assertEquals(128, Fixtures.handoffs().getFirst().displayName.length());
		assertNull(Fixtures.handoffs().getFirst().keycloakSid);
	}

	@Test
	void limitsHowManyCodesACustomerCanMint() {
		for (int i = 0; i < Handoff.RATE_LIMIT; i++) {
			Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/handoffs").then().statusCode(201);
		}
		Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/handoffs").then().statusCode(429).body("code", equalTo("rate_limited"));
		// Another customer is unaffected.
		Fixtures.customer("another-subject").post("/api/v1/app/handoffs").then().statusCode(201);
		assertEquals(Handoff.RATE_LIMIT + 1, Fixtures.handoffs().size());
	}

	@Test
	void keepsALongEmailWhole() {
		String email = "a".repeat(200) + "@example.com";
		String token = Fixtures.customerToken(CUSTOMER_SUB, c -> c.claim("email", email));
		given().auth().oauth2(token).post("/api/v1/app/handoffs").then().statusCode(201);
		assertEquals(email, Fixtures.handoffs().getFirst().email);
	}

	@Test
	void theLimitHoldsWhenRequestsRaceEachOther() throws Exception {
		Fixtures.customerRow(CUSTOMER_SUB);
		var pool = Executors.newFixedThreadPool(12);
		try {
			List<Callable<Integer>> calls = IntStream.range(0, 12)
					.<Callable<Integer>>mapToObj(i -> () -> Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/handoffs").statusCode())
					.toList();
			long created = 0;
			for (Future<Integer> f : pool.invokeAll(calls)) if (f.get() == 201) created++;
			assertEquals(Handoff.RATE_LIMIT, created);
			assertEquals(Handoff.RATE_LIMIT, Fixtures.handoffs().size());
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void purgesCodesExpiredForLong() {
		Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/handoffs").then().statusCode(201);
		QuarkusTransaction.requiringNew().run(() -> Handoff.update("expiresAt = ?1, issuedAt = ?1", Instant.now().minus(Duration.ofHours(2))));
		Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/handoffs").then().statusCode(201);
		assertEquals(1, Fixtures.handoffs().size());
	}

	@Test
	void refusesARequestWithoutACustomerToken() {
		given().post("/api/v1/app/handoffs").then().statusCode(401);
		given().auth().oauth2(Fixtures.BOOKING_TOKEN).post("/api/v1/app/handoffs").then().statusCode(401);
		given().auth().oauth2(Fixtures.customerToken(CUSTOMER_SUB, c -> c.claim("azp", "poc-booking")))
				.post("/api/v1/app/handoffs").then().statusCode(401);
		assertEquals(0, Fixtures.handoffs().size());
		assertEquals(0, Fixtures.customers().size());
	}

	@Test
	void doesNotAcceptACustomerTokenOnTheBookingApi() {
		Fixtures.customer(CUSTOMER_SUB).body("{\"code\":\"x\"}").post("/api/v1/booking/handoffs/redeem").then().statusCode(401);
	}
}
