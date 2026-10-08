package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Smoke test of the packaged app (the native image in CI): migrations run, entities map, JSON
 * serializes, and the token filter is active. HTTP only; the {@code @QuarkusTest}s cover behaviour.
 */
@QuarkusIntegrationTest
class AdminApiIT {
	@Test
	void servesTheAdminApi() {
		given().get("/q/health/ready").then().statusCode(200);
		given().get("/api/v1/admin/staff-access/x").then().statusCode(401);
		Fixtures.worker().get("/api/v1/admin/staff-access/x").then().statusCode(200)
				.body("status", equalTo("none"));
		Fixtures.worker().body(Map.of("sub", "x", "email", "nobody@x.com"))
				.post("/api/v1/admin/staff-access/sign-ins").then().statusCode(200).body("status", equalTo("none"));
		Fixtures.worker().get("/api/v1/admin/customers").then().statusCode(403);
		Fixtures.worker().get("/api/v1/admin/staff").then().statusCode(403)
				.contentType("application/problem+json").body("code", equalTo("forbidden"));
	}
}
