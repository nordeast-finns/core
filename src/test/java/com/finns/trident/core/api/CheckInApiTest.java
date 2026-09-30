package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.CheckIn;
import com.finns.trident.core.model.Qr;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class CheckInApiTest {
	static final String GATE = "gym-main-entrance";

	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	@Test
	void grantsAFreshQrOnce() {
		String qr = given().post("/api/v1/app/qrs").then().statusCode(201).extract().path("qr");

		scan(qr).then().statusCode(200).body("result", equalTo("granted")).body("$", not(hasKey("reason")));
		scan(qr).then().statusCode(200).body("result", equalTo("denied")).body("reason", equalTo("used"));

		Qr stored = Fixtures.qrs().getFirst();
		assertNotNull(stored.usedAt);
		assertEquals(GATE, stored.usedGate);

		List<CheckIn> checkIns = Fixtures.checkIns();
		assertEquals(2, checkIns.size());
		assertEquals(1, checkIns.stream().filter(c -> c.result == CheckIn.Result.GRANTED).count());
		checkIns.forEach(c -> {
			assertEquals(stored.id, c.qrId);
			assertEquals(GATE, c.gateId);
			assertEquals(CheckIn.Source.ONLINE, c.source);
		});
	}

	@Test
	void deniesAnExpiredQr() {
		String qr = Fixtures.qr(Instant.now().minusSeconds(1), null);
		scan(qr).then().statusCode(200).body("result", equalTo("denied")).body("reason", equalTo("expired"));
		assertNull(Fixtures.qrs().getFirst().usedAt);
		assertEquals(CheckIn.Reason.EXPIRED, Fixtures.checkIns().getFirst().reason);
	}

	@Test
	void deniesAUsedQrEvenBeforeItExpires() {
		String qr = Fixtures.qr(Instant.now().plusSeconds(30), Instant.now().minusSeconds(5));
		scan(qr).then().statusCode(200).body("reason", equalTo("used"));
		assertEquals("earlier-gate", Fixtures.qrs().getFirst().usedGate);
	}

	@Test
	void deniesAnUnknownQr() {
		scan("FINNS1:" + "A".repeat(43)).then().statusCode(200)
				.body("result", equalTo("denied")).body("reason", equalTo("unknown"));
		CheckIn c = Fixtures.checkIns().getFirst();
		assertNull(c.qrId);
		assertEquals(CheckIn.Reason.UNKNOWN, c.reason);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "https://example.com", "FINNS1:", "FINNS1:short", "FINNS2:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
			"finns1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "FINNS1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"})
	void deniesAndRecordsTextThatIsNotAQr(String qr) {
		scan(qr).then().statusCode(200).body("result", equalTo("denied")).body("reason", equalTo("malformed"));
		assertEquals(CheckIn.Reason.MALFORMED, Fixtures.checkIns().getFirst().reason);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "Gym", "gym entrance", "gym_entrance", "é"})
	void refusesABadGateId(String gateId) {
		Fixtures.gate().body(Map.of("gateId", gateId, "qr", "x")).post("/api/v1/gate/check-ins").then()
				.statusCode(400).contentType("application/problem+json").body("code", equalTo("malformed"));
		assertEquals(0, Fixtures.checkIns().size());
	}

	@Test
	void refusesATooLongGateId() {
		Fixtures.gate().body(Map.of("gateId", "a".repeat(65), "qr", "x")).post("/api/v1/gate/check-ins").then()
				.statusCode(400).body("code", equalTo("malformed"));
	}

	@Test
	void refusesAMissingField() {
		Map<String, String> body = new HashMap<>();
		body.put("gateId", GATE);
		Fixtures.gate().body(body).post("/api/v1/gate/check-ins").then().statusCode(400).body("code", equalTo("malformed"));
		Fixtures.gate().body(Map.of("qr", "x")).post("/api/v1/gate/check-ins").then().statusCode(400);
		Fixtures.gate().body("not json").post("/api/v1/gate/check-ins").then().statusCode(400);
		Fixtures.gate().post("/api/v1/gate/check-ins").then().statusCode(400);
	}

	private static io.restassured.response.Response scan(String qr) {
		return Fixtures.gate().body(Map.of("gateId", GATE, "qr", qr)).post("/api/v1/gate/check-ins");
	}
}
