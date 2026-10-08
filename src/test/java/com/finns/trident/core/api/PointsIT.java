package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Smoke test of the packaged app: a new customer appears in the points feed, and can be credited,
 * debited and refunded.
 */
@QuarkusIntegrationTest
class PointsIT {
	@Test
	void postsPoints() {
		given().get("/api/v1/points/events").then().statusCode(401);
		// The app's first request for a new customer creates them, and the feed announces it.
		Fixtures.customer("points-it-" + UUID.randomUUID()).post("/api/v1/app/qrs").then().statusCode(201);
		String customerId = lastCreatedCustomer();

		Map<String, Object> credit = Map.of("customerId", customerId, "points", 100, "reason", "smoke");
		Fixtures.points("it-c-" + customerId).body(credit).post("/api/v1/points/credits").then().statusCode(201)
				.body("balance", equalTo(100));
		Fixtures.points("it-c-" + customerId).body(credit).post("/api/v1/points/credits").then().statusCode(201)
				.header("Idempotent-Replayed", "true");
		String debitId = Fixtures.points("it-d-" + customerId).body(Map.of("customerId", customerId, "points", 30))
				.post("/api/v1/points/debits").then().statusCode(201).body("balance", equalTo(70))
				.extract().path("transactionId");
		Fixtures.points("it-r-" + customerId).post("/api/v1/points/transactions/" + debitId + "/refund").then()
				.statusCode(201).body("balance", equalTo(100));
		Fixtures.points().get("/api/v1/points/customers/" + customerId).then().statusCode(200)
				.body("balance", equalTo(100)).body("seq", equalTo(3));
	}

	private static String lastCreatedCustomer() {
		String after = null;
		String last = null;
		while (true) {
			var request = Fixtures.points().queryParam("limit", 500);
			if (after != null) request.queryParam("after", after);
			JsonPath page = request.get("/api/v1/points/events").then().statusCode(200).extract().jsonPath();
			List<Map<String, Object>> events = page.getList("events");
			if (events.isEmpty()) return last;
			for (Map<String, Object> e : events) {
				if ("customer.created".equals(e.get("type"))) last = (String) e.get("customerId");
			}
			after = page.getString("next");
		}
	}
}
