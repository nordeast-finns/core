package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.Qr;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.JwtClaimsBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static com.finns.trident.core.Fixtures.CUSTOMER_SUB;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class QrApiTest {
	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	@Test
	void issuesAOneTimeQrToTheCustomer() {
		String qr = Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/qrs").then().statusCode(201)
				.body("qr", matchesPattern("FINNS1:[A-Za-z0-9_-]{43}"))
				.body("ttlSeconds", equalTo(60))
				.extract().path("qr");

		Customer customer = Fixtures.customers().getFirst();
		assertEquals(CUSTOMER_SUB, customer.keycloakSub);
		List<Qr> rows = Fixtures.qrs();
		assertEquals(1, rows.size());
		Qr row = rows.getFirst();
		assertEquals(customer.id, row.customerId);
		assertNull(row.usedAt);
		assertEquals(Duration.ofSeconds(60), Duration.between(row.issuedAt, row.expiresAt));
		assertTrue(row.expiresAt.isAfter(Instant.now()));
		// Only the hash is stored.
		assertEquals(32, row.tokenHash.length);
		assertTrue(Qr.decode(qr).isPresent());
	}

	@Test
	void createsEachCustomerOnce() {
		String a = Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/qrs").then().statusCode(201).extract().path("qr");
		String b = Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/qrs").then().statusCode(201).extract().path("qr");
		Fixtures.customer("another-subject").post("/api/v1/app/qrs").then().statusCode(201);

		assertNotEquals(a, b);
		assertEquals(2, Fixtures.customers().size());
		long first = Fixtures.customers().stream().filter(c -> c.keycloakSub.equals(CUSTOMER_SUB)).findFirst().orElseThrow().id;
		assertEquals(2, Fixtures.qrs().stream().filter(q -> q.customerId == first).count());
	}

	@Test
	void keepsTheProfileFromTheLatestToken() {
		issueWith(c -> c.claim("name", " Dewi Lestari ").claim("email", "dewi@example.com"));
		Customer customer = Fixtures.customers().getFirst();
		assertEquals("Dewi Lestari", customer.displayName);
		assertEquals("dewi@example.com", customer.email);

		// Changed in Keycloak: the next request updates the copy, falling back to preferred_username.
		issueWith(c -> c.claim("preferred_username", "dewi").claim("email", "dewi@new.example"));
		customer = Fixtures.customers().getFirst();
		assertEquals("dewi", customer.displayName);
		assertEquals("dewi@new.example", customer.email);

		// The token is the latest word: a claim it no longer carries clears the copy.
		issueWith(UnaryOperator.identity());
		customer = Fixtures.customers().getFirst();
		assertNull(customer.displayName);
		assertNull(customer.email);
	}

	@Test
	void cutsAnOverlongNameAndDropsAnOverlongEmail() {
		// 😀 is two UTF-16 chars but one character to Postgres; a cut never splits it.
		issueWith(c -> c.claim("name", "😀".repeat(200)).claim("email", "y".repeat(400) + "@example.com"));
		Customer customer = Fixtures.customers().getFirst();
		assertEquals("😀".repeat(Customer.Profile.DISPLAY_NAME_MAX), customer.displayName);
		assertNull(customer.email);
	}

	@Test
	void dropsControlCharactersSoTheWriteCantFail() {
		issueWith(c -> c.claim("name", "De\0wi\nLestari").claim("email", "\0\t"));
		Customer customer = Fixtures.customers().getFirst();
		assertEquals("DewiLestari", customer.displayName);
		assertNull(customer.email);
	}

	private static void issueWith(UnaryOperator<JwtClaimsBuilder> tweak) {
		given().auth().oauth2(Fixtures.customerToken(CUSTOMER_SUB, tweak)).post("/api/v1/app/qrs").then()
				.statusCode(201);
	}

	@Test
	void refusesARequestWithoutAToken() {
		given().post("/api/v1/app/qrs").then().statusCode(401);
		// Unknown paths too, so callers can't probe which endpoints exist.
		given().get("/api/v1/app/nothing-here").then().statusCode(401);
		given().get("/api/v1/app").then().statusCode(401);
		assertEquals(0, Fixtures.qrs().size());
		assertEquals(0, Fixtures.customers().size());
	}

	@Test
	void refusesTokensNotIssuedToTheCustomerApp() {
		refused(c -> c.issuer("https://elsewhere.test/realms/finns"));
		refused(c -> c.expiresAt(Instant.now().minusSeconds(120)));
		// Another client of the realm, and an ID token rather than an access token.
		refused(c -> c.claim("azp", "account-console"));
		refused(c -> c.claim("typ", "ID"));
		refused(c -> c.remove("sub"));
		assertEquals(0, Fixtures.customers().size());
	}

	@Test
	void refusesATamperedToken() {
		String token = Fixtures.customerToken(CUSTOMER_SUB, UnaryOperator.identity());
		String[] parts = token.split("\\.");
		// Same header and claims, signature of a different token.
		String other = Fixtures.customerToken("someone-else", UnaryOperator.identity()).split("\\.")[2];
		given().auth().oauth2(parts[0] + "." + parts[1] + "." + other).post("/api/v1/app/qrs").then().statusCode(401);
	}

	@Test
	void refusesTheOtherApisTokens() {
		given().auth().oauth2(Fixtures.GATE_TOKEN).post("/api/v1/app/qrs").then().statusCode(401);
		given().auth().oauth2(Fixtures.TOKEN).post("/api/v1/app/qrs").then().statusCode(401);
	}

	@Test
	void doesNotAcceptACustomerTokenOnTheGateApi() {
		Fixtures.customer(CUSTOMER_SUB).body(Map.of("gateId", "gym", "qr", "x"))
				.post("/api/v1/gate/check-ins").then().statusCode(401);
	}

	@Test
	void allowsTheAppsWebOrigin() {
		given().header("Origin", "http://localhost:5173")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "authorization,content-type")
				.options("/api/v1/app/qrs").then()
				.statusCode(200)
				.header("Access-Control-Allow-Origin", "http://localhost:5173");
	}

	@Test
	void refusesOtherOrigins() {
		given().header("Origin", "https://evil.example")
				.header("Access-Control-Request-Method", "POST")
				.options("/api/v1/app/qrs").then()
				.header("Access-Control-Allow-Origin", nullValue());
	}

	private static void refused(UnaryOperator<JwtClaimsBuilder> tweak) {
		given().auth().oauth2(Fixtures.customerToken(CUSTOMER_SUB, tweak)).post("/api/v1/app/qrs").then().statusCode(401);
	}
}
