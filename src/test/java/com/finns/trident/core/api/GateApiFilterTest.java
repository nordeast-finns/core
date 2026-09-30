package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.emptyString;

@QuarkusTest
class GateApiFilterTest {
	static final Map<String, String> SCAN = Map.of("gateId", "g", "qr", "x");

	@Test
	void rejectsMissingToken() {
		given().contentType(JSON).body(SCAN).post("/api/v1/gate/check-ins").then().statusCode(401).body(emptyString());
	}

	@Test
	void rejectsWrongToken() {
		given().header("Authorization", "Bearer " + Fixtures.GATE_TOKEN.replace('t', 'x')).contentType(JSON).body(SCAN)
				.post("/api/v1/gate/check-ins").then().statusCode(401);
	}

	@Test
	void rejectsTheAdminToken() {
		Fixtures.worker().body(SCAN).post("/api/v1/gate/check-ins").then().statusCode(401);
	}

	@Test
	void theGateTokenOpensNoAdminEndpoint() {
		Fixtures.gate().get("/api/v1/admin/staff-access/x").then().statusCode(401);
	}

	@Test
	void protectsUnknownGatePathsSoTheyCantBeProbed() {
		given().get("/api/v1/gate/nothing-here").then().statusCode(401);
		given().get("/api/v1/gate").then().statusCode(401);
	}

	@Test
	void acceptsValidToken() {
		Fixtures.gate().body(SCAN).post("/api/v1/gate/check-ins").then().statusCode(200);
	}
}
