package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.CheckIn;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsTxn;
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

import static com.finns.trident.core.Fixtures.CUSTOMER_SUB;
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
	void grantsAFreshQrOnceAndRecordsWhose() {
		String qr = Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/qrs").then().statusCode(201).extract().path("qr");
		Customer customer = Fixtures.customers().getFirst();

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
			assertEquals(customer.id, c.customerId);
			assertEquals(GATE, c.gateId);
			assertEquals(CheckIn.Source.ONLINE, c.source);
		});
	}

	@Test
	void aGrantedCheckInEarnsPointsOnce() {
		String qr = Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/qrs").then().statusCode(201).extract().path("qr");
		Customer customer = Fixtures.customers().getFirst();

		scan(qr).then().body("result", equalTo("granted"));
		scan(qr).then().body("result", equalTo("denied"));

		assertEquals(10, Fixtures.balance(customer.id));
		PointsTxn txn = Fixtures.pointsTxns().getFirst();
		assertEquals(PointsTxn.Client.CORE, txn.client);
		assertEquals(PointsTxn.CHECK_IN_REASON, txn.reason);
		assertEquals(Fixtures.qrs().getFirst().id.toString(), txn.reference);

		// Every granted check-in earns, not just the first.
		String next = Fixtures.customer(CUSTOMER_SUB).post("/api/v1/app/qrs").then().statusCode(201).extract().path("qr");
		scan(next).then().body("result", equalTo("granted"));
		assertEquals(20, Fixtures.balance(customer.id));
	}

	@Test
	void aDeniedCheckInEarnsNothing() {
		Customer customer = Fixtures.customerRow(CUSTOMER_SUB);
		scan(Fixtures.qr(customer.id, Instant.now().minusSeconds(1), null)).then().body("result", equalTo("denied"));
		scan(Fixtures.qr(customer.id, Instant.now().plusSeconds(30), Instant.now().minusSeconds(5)))
				.then().body("result", equalTo("denied"));
		assertEquals(0, Fixtures.pointsTxns().size());
	}

	@Test
	void deniesAnExpiredQr() {
		Customer customer = Fixtures.customerRow(CUSTOMER_SUB);
		String qr = Fixtures.qr(customer.id, Instant.now().minusSeconds(1), null);
		scan(qr).then().statusCode(200).body("result", equalTo("denied")).body("reason", equalTo("expired"));
		assertNull(Fixtures.qrs().getFirst().usedAt);
		CheckIn c = Fixtures.checkIns().getFirst();
		assertEquals(CheckIn.Reason.EXPIRED, c.reason);
		assertEquals(customer.id, c.customerId);
	}

	@Test
	void deniesAUsedQrEvenBeforeItExpires() {
		Customer customer = Fixtures.customerRow(CUSTOMER_SUB);
		String qr = Fixtures.qr(customer.id, Instant.now().plusSeconds(30), Instant.now().minusSeconds(5));
		scan(qr).then().statusCode(200).body("reason", equalTo("used"));
		assertEquals("earlier-gate", Fixtures.qrs().getFirst().usedGate);
		assertEquals(customer.id, Fixtures.checkIns().getFirst().customerId);
	}

	@Test
	void deniesAnUnknownQr() {
		scan("FINNS1:" + "A".repeat(43)).then().statusCode(200)
				.body("result", equalTo("denied")).body("reason", equalTo("unknown"));
		CheckIn c = Fixtures.checkIns().getFirst();
		assertNull(c.qrId);
		assertNull(c.customerId);
		assertEquals(CheckIn.Reason.UNKNOWN, c.reason);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "https://example.com", "FINNS1:", "FINNS1:short", "FINNS2:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
			"finns1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "FINNS1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"})
	void deniesAndRecordsTextThatIsNotAQr(String qr) {
		scan(qr).then().statusCode(200).body("result", equalTo("denied")).body("reason", equalTo("malformed"));
		CheckIn c = Fixtures.checkIns().getFirst();
		assertEquals(CheckIn.Reason.MALFORMED, c.reason);
		assertNull(c.customerId);
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
