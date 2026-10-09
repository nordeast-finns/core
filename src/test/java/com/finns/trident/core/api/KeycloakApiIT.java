package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Smoke test of the packaged app (the native image in CI): the Keycloak notice's filter, JSON body and
 * outbound call to Keycloak, which isn't running here. The {@code @QuarkusTest}s cover behaviour.
 */
@QuarkusIntegrationTest
class KeycloakApiIT {
	@Test
	void servesKeycloaksNotices() {
		given().body("{}").post("/api/v1/keycloak/user-changes").then().statusCode(401);
		Fixtures.keycloak().body("{\"userId\":7}").post("/api/v1/keycloak/user-changes").then().statusCode(400)
				.body("code", equalTo("malformed"));
		Fixtures.keycloak().body(Map.of("userId", "6c1f7a52-8a77-4a43-9f43-6f5e0b6f2c11"))
				.post("/api/v1/keycloak/user-changes").then().statusCode(503)
				.contentType("application/problem+json").body("code", equalTo("unavailable"));
	}
}
