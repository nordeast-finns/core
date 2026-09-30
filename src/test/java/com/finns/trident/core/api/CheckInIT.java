package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Smoke test of the packaged app: a QR code is issued, granted once, then denied. */
@QuarkusIntegrationTest
class CheckInIT {
	@Test
	void checksInWithAQr() {
		String qr = given().post("/api/v1/app/qrs").then().statusCode(201).extract().path("qr");
		Map<String, String> scan = Map.of("gateId", "smoke-test", "qr", qr);
		given().contentType("application/json").body(scan).post("/api/v1/gate/check-ins").then().statusCode(401);
		Fixtures.gate().body(scan).post("/api/v1/gate/check-ins").then().statusCode(200)
				.body("result", equalTo("granted"));
		Fixtures.gate().body(scan).post("/api/v1/gate/check-ins").then().statusCode(200)
				.body("result", equalTo("denied")).body("reason", equalTo("used"));
	}
}
