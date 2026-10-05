package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.finns.trident.core.Fixtures.CUSTOMER_SUB;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class LogoutApiTest {
	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	private static String tokenWithSid(String sub, String sid) {
		return Fixtures.customerToken(sub, c -> c.claim("sid", sid).claim("name", "Dewi"));
	}

	@Test
	void revokesTheUnusedCodesOfTheTokensOwnSession() {
		given().auth().oauth2(tokenWithSid(CUSTOMER_SUB, "s1")).post("/api/v1/app/handoffs").then().statusCode(201);
		given().auth().oauth2(tokenWithSid(CUSTOMER_SUB, "s2")).post("/api/v1/app/handoffs").then().statusCode(201);

		given().auth().oauth2(tokenWithSid(CUSTOMER_SUB, "s1")).post("/api/v1/app/logout").then().statusCode(204);

		var rows = Fixtures.handoffs();
		var s1 = rows.stream().filter(h -> "s1".equals(h.keycloakSid)).findFirst().orElseThrow();
		var s2 = rows.stream().filter(h -> "s2".equals(h.keycloakSid)).findFirst().orElseThrow();
		assertNotNull(s1.usedAt);
		assertNull(s1.displayName);
		assertNull(s2.usedAt);
		assertEquals("Dewi", s2.displayName);
	}

	@Test
	void aRevokedCodeCannotBeRedeemed() {
		String code = given().auth().oauth2(tokenWithSid(CUSTOMER_SUB, "s1")).post("/api/v1/app/handoffs").then().extract().path("code");
		given().auth().oauth2(tokenWithSid(CUSTOMER_SUB, "s1")).post("/api/v1/app/logout").then().statusCode(204);
		Fixtures.booking().body("{\"code\":\"" + code + "\"}").post("/api/v1/booking/handoffs/redeem").then().statusCode(404);
	}

	@Test
	void anotherCustomerCannotRevokeASession() {
		given().auth().oauth2(tokenWithSid(CUSTOMER_SUB, "s1")).post("/api/v1/app/handoffs").then().statusCode(201);
		// The sid comes from the caller's own token, so naming someone else's in the body has no effect.
		given().auth().oauth2(tokenWithSid("another-subject", "s2")).contentType("application/json")
				.body("{\"sid\":\"s1\"}").post("/api/v1/app/logout").then().statusCode(204);
		assertNull(Fixtures.handoffs().getFirst().usedAt);
	}

	@Test
	void succeedsWhenTheTokenHasNoSession() {
		Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/logout").then().statusCode(204);
	}

	@Test
	void needsACustomerToken() {
		given().post("/api/v1/app/logout").then().statusCode(401);
	}
}
