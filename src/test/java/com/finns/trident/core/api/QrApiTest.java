package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Qr;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class QrApiTest {
	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	@Test
	void issuesAOneTimeQr() {
		String qr = given().post("/api/v1/app/qrs").then().statusCode(201)
				.body("qr", matchesPattern("FINNS1:[A-Za-z0-9_-]{43}"))
				.body("ttlSeconds", equalTo(60))
				.extract().path("qr");

		List<Qr> rows = Fixtures.qrs();
		assertEquals(1, rows.size());
		Qr row = rows.getFirst();
		assertNull(row.usedAt);
		assertEquals(Duration.ofSeconds(60), Duration.between(row.issuedAt, row.expiresAt));
		assertTrue(row.expiresAt.isAfter(Instant.now()));
		// Only the hash is stored.
		assertEquals(32, row.tokenHash.length);
		assertTrue(Qr.decode(qr).isPresent());
	}

	@Test
	void issuesADifferentQrEachTime() {
		String a = given().post("/api/v1/app/qrs").then().extract().path("qr");
		String b = given().post("/api/v1/app/qrs").then().extract().path("qr");
		assertTrue(!a.equals(b));
	}

	@Test
	void allowsTheAppsWebOrigin() {
		given().header("Origin", "http://localhost:5173")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "content-type")
				.options("/api/v1/app/qrs").then()
				.header("Access-Control-Allow-Origin", "http://localhost:5173");
	}

	@Test
	void refusesOtherOrigins() {
		given().header("Origin", "https://evil.example")
				.header("Access-Control-Request-Method", "POST")
				.options("/api/v1/app/qrs").then()
				.header("Access-Control-Allow-Origin", nullValue());
	}
}
