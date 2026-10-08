package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsTxn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class PointsApiTest {
	private Customer customer;

	@BeforeEach
	void reset() {
		Fixtures.reset();
		customer = Fixtures.customerRow(Fixtures.CUSTOMER_SUB);
	}

	private String id() {
		return customer.publicId.toString();
	}

	private static Map<String, Object> posting(String customerId, Object points) {
		Map<String, Object> body = new HashMap<>();
		body.put("customerId", customerId);
		body.put("points", points);
		return body;
	}

	private ValidatableResponse credit(String key, long points) {
		return Fixtures.points(key).body(posting(id(), points)).post("/api/v1/points/credits").then();
	}

	private ValidatableResponse debit(String key, long points) {
		return Fixtures.points(key).body(posting(id(), points)).post("/api/v1/points/debits").then();
	}

	private ValidatableResponse refund(String key, String transactionId) {
		return Fixtures.points(key).body("{}").post("/api/v1/points/transactions/" + transactionId + "/refund").then();
	}

	private long balance() {
		return Fixtures.points().get("/api/v1/points/customers/" + id()).then().statusCode(200)
				.extract().<Number>path("balance").longValue();
	}

	@Test
	void aCustomerWithoutPostingsHasZeroPoints() {
		Fixtures.points().get("/api/v1/points/customers/" + id()).then().statusCode(200)
				.header("Cache-Control", "no-store")
				.body("customerId", equalTo(id()))
				.body("balance", equalTo(0))
				.body("seq", equalTo(0));
	}

	@Test
	void anUnknownCustomerIsNotFound() {
		String unknown = UUID.randomUUID().toString();
		Fixtures.points().get("/api/v1/points/customers/" + unknown).then().statusCode(404).body("code", equalTo("not_found"));
		Fixtures.points().get("/api/v1/points/customers/not-a-uuid").then().statusCode(404);
		// UUID.fromString accepts this short form of 00000001-0002-0003-0004-000000000005; the API doesn't.
		Fixtures.points().get("/api/v1/points/customers/1-2-3-4-5").then().statusCode(404);
		Fixtures.points("k1").body(posting(unknown, 5)).post("/api/v1/points/credits").then().statusCode(404);
		Fixtures.points("k1").body(posting("1-2-3-4-5", 5)).post("/api/v1/points/credits").then().statusCode(422)
				.body("fields.customerId", equalTo("invalid"));
		// UUIDs are case-insensitive; responses spell them in lowercase.
		Fixtures.points().get("/api/v1/points/customers/" + id().toUpperCase()).then().statusCode(200)
				.body("customerId", equalTo(id()));
	}

	@Test
	void creditsDebitsAndRefunds() {
		Map<String, Object> body = posting(id(), 100);
		body.put("reason", "sota.welcome");
		body.put("reference", "sota-crd-1");
		String creditId = Fixtures.points("k-credit").body(body).post("/api/v1/points/credits").then().statusCode(201)
				.header("Cache-Control", "no-store")
				.header("Idempotent-Replayed", nullValue())
				.body("kind", equalTo("credit"))
				.body("customerId", equalTo(id()))
				.body("points", equalTo(100))
				.body("balance", equalTo(100))
				.body("seq", equalTo(1))
				.body("reason", equalTo("sota.welcome"))
				.body("reference", equalTo("sota-crd-1"))
				.body("refundOf", nullValue())
				.body("recordedAt", notNullValue())
				.extract().path("transactionId");

		String debitId = debit("k-debit", 40).statusCode(201)
				.body("kind", equalTo("debit")).body("points", equalTo(-40)).body("balance", equalTo(60)).body("seq", equalTo(2))
				.body("reason", nullValue()).body("reference", nullValue())
				.extract().path("transactionId");

		refund("k-refund", debitId).statusCode(201)
				.body("kind", equalTo("refund")).body("points", equalTo(40)).body("balance", equalTo(100)).body("seq", equalTo(3))
				.body("refundOf", equalTo(debitId));

		refund("k-refund-credit", creditId).statusCode(201)
				.body("points", equalTo(-100)).body("balance", equalTo(0)).body("refundOf", equalTo(creditId));
		assertEquals(0, balance());
	}

	@Test
	void aDebitCantTakeTheBalanceBelowZero() {
		credit("k1", 50).statusCode(201);
		debit("k2", 51).statusCode(409).body("code", equalTo("insufficient_points"));
		debit("k3", 50).statusCode(201).body("balance", equalTo(0));
		debit("k4", 1).statusCode(409).body("code", equalTo("insufficient_points"));
		assertEquals(0, balance());
	}

	@Test
	void aCustomerWithoutAnAccountCantBeDebited() {
		debit("k1", 1).statusCode(409).body("code", equalTo("insufficient_points"));
		Fixtures.points().get("/api/v1/points/customers/" + id()).then().body("balance", equalTo(0)).body("seq", equalTo(0));
	}

	@Test
	void aFailedPostingCanBeRetriedWithTheSameKey() {
		debit("k1", 10).statusCode(409);
		credit("k2", 10).statusCode(201);
		// Nothing was stored for k1, so the retry is evaluated afresh.
		debit("k1", 10).statusCode(201).header("Idempotent-Replayed", nullValue()).body("balance", equalTo(0));
	}

	@Test
	void aRetryReplaysTheOriginalTransaction() {
		String first = credit("k1", 30).statusCode(201).extract().asString();
		credit("k2", 5).statusCode(201);
		String again = credit("k1", 30).statusCode(201).header("Idempotent-Replayed", "true").extract().asString();
		assertEquals(first, again);
		assertEquals(35, balance());
	}

	@Test
	void aKeyCantBeReusedForADifferentRequest() {
		credit("k1", 30).statusCode(201);
		credit("k1", 31).statusCode(422).body("code", equalTo("idempotency_mismatch"));
		debit("k1", 30).statusCode(422).body("code", equalTo("idempotency_mismatch"));
		Map<String, Object> withReason = posting(id(), 30);
		withReason.put("reason", "other");
		Fixtures.points("k1").body(withReason).post("/api/v1/points/credits").then().statusCode(422);
		String creditId = credit("k2", 5).statusCode(201).extract().path("transactionId");
		refund("k1", creditId).statusCode(422).body("code", equalTo("idempotency_mismatch"));
		assertEquals(35, balance());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "has space", "é", "x12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"})
	void needsAValidIdempotencyKey(String key) {
		Fixtures.points().header("Idempotency-Key", key).body(posting(id(), 1)).post("/api/v1/points/credits").then()
				.statusCode(400).body("code", equalTo("malformed"));
	}

	@Test
	void needsAnIdempotencyKeyOnEveryPosting() {
		Fixtures.points().body(posting(id(), 1)).post("/api/v1/points/credits").then().statusCode(400);
		Fixtures.points().body(posting(id(), 1)).post("/api/v1/points/debits").then().statusCode(400);
		Fixtures.points().body("{}").post("/api/v1/points/transactions/" + UUID.randomUUID() + "/refund").then().statusCode(400);
		credit("x".repeat(128), 1).statusCode(201);
	}

	@Test
	void validatesThePosting() {
		Map<String, Object> bad = new HashMap<>();
		bad.put("reason", "has space");
		bad.put("reference", "");
		Fixtures.points("k1").body(bad).post("/api/v1/points/credits").then().statusCode(422)
				.body("code", equalTo("invalid"))
				.body("fields.customerId", equalTo("required"))
				.body("fields.points", equalTo("required"))
				.body("fields.reason", equalTo("invalid"))
				.body("fields.reference", equalTo("invalid"));
		for (Object points : List.of(0, -1, 1_000_000_001L, 1.5, 1.0, "10", "ten", true, Map.of(), new java.math.BigInteger("99999999999999999999"))) {
			Fixtures.points("k1").body(posting(id(), points)).post("/api/v1/points/debits").then().statusCode(422)
					.body("fields.points", equalTo("invalid"));
		}
		Fixtures.points("k1").body(posting("nope", 1)).post("/api/v1/points/credits").then().statusCode(422)
				.body("fields.customerId", equalTo("invalid"));
		Fixtures.points("k1").body("{\"customerId\":\"" + id() + "\",\"points\":null}").post("/api/v1/points/credits").then()
				.statusCode(422).body("fields.points", equalTo("required"));
		Fixtures.points("k1").post("/api/v1/points/credits").then().statusCode(400);
		Fixtures.points("k1").body("not json").post("/api/v1/points/credits").then().statusCode(400);
		credit("k1", 1_000_000_000L).statusCode(201);
	}

	@Test
	void refundsOnlyOnceAndNeverARefund() {
		credit("k1", 100).statusCode(201);
		String debitId = debit("k2", 10).statusCode(201).extract().path("transactionId");
		String refundId = refund("k3", debitId).statusCode(201).extract().path("transactionId");
		refund("k3", debitId).statusCode(201).header("Idempotent-Replayed", "true");
		refund("k4", debitId).statusCode(409).body("code", equalTo("already_refunded"));
		refund("k5", refundId).statusCode(409).body("code", equalTo("not_refundable"));
		assertEquals(100, balance());
	}

	@Test
	void aCreditWhosePointsWereSpentCantBeRefunded() {
		String creditId = credit("k1", 100).statusCode(201).extract().path("transactionId");
		debit("k2", 60).statusCode(201);
		refund("k3", creditId).statusCode(409).body("code", equalTo("insufficient_points"));
		assertEquals(40, balance());
		// Not refunded, so it can be once the points are back.
		credit("k4", 60).statusCode(201);
		refund("k3", creditId).statusCode(201).body("balance", equalTo(0));
	}

	@Test
	void refundingAnUnknownTransactionIsNotFound() {
		refund("k1", UUID.randomUUID().toString()).statusCode(404).body("code", equalTo("not_found"));
		refund("k1", "nope").statusCode(404);
		Fixtures.points("k1").body("{\"reference\":\"has space\"}").post("/api/v1/points/transactions/" + UUID.randomUUID() + "/refund")
				.then().statusCode(422).body("fields.reference", equalTo("invalid"));
		// A refund without a body is fine.
		String creditId = credit("k2", 1).statusCode(201).extract().path("transactionId");
		Fixtures.points("k3").post("/api/v1/points/transactions/" + creditId + "/refund").then().statusCode(201);
	}

	@Test
	void concurrentDebitsNeverOverdraw() throws Exception {
		credit("seed", 50).statusCode(201);
		var pool = Executors.newFixedThreadPool(10);
		try {
			List<Callable<Integer>> calls = IntStream.range(0, 20)
					.<Callable<Integer>>mapToObj(i -> () -> debit("d" + i, 10).extract().statusCode()).toList();
			Map<Integer, Long> statuses = pool.invokeAll(calls).stream().map(PointsApiTest::get)
					.collect(Collectors.groupingBy(s -> s, Collectors.counting()));
			assertEquals(Map.of(201, 5L, 409, 15L), statuses);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(0, balance());
		Fixtures.points().get("/api/v1/points/customers/" + id()).then().body("seq", equalTo(6));
	}

	@Test
	void concurrentRetriesPostOnce() throws Exception {
		var pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<String>> calls = IntStream.range(0, 8)
					.<Callable<String>>mapToObj(i -> () -> credit("same", 7).statusCode(201).extract().path("transactionId")).toList();
			List<String> ids = pool.invokeAll(calls).stream().map(PointsApiTest::get).distinct().toList();
			assertEquals(1, ids.size());
		} finally {
			pool.shutdownNow();
		}
		assertEquals(7, balance());
	}

	@Test
	void concurrentRefundsOfOneTransactionRefundOnce() throws Exception {
		credit("k1", 100).statusCode(201);
		String debitId = debit("k2", 100).statusCode(201).extract().path("transactionId");
		var pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Integer>> calls = IntStream.range(0, 8)
					.<Callable<Integer>>mapToObj(i -> () -> refund("r" + i, debitId).extract().statusCode()).toList();
			Map<Integer, Long> statuses = pool.invokeAll(calls).stream().map(PointsApiTest::get)
					.collect(Collectors.groupingBy(s -> s, Collectors.counting()));
			assertEquals(Map.of(201, 1L, 409, 7L), statuses);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(100, balance());
	}

	@Test
	void reconcilesABusinessDate() {
		String c = credit("k1", 100).statusCode(201).extract().path("transactionId");
		String d1 = debit("k2", 30).statusCode(201).extract().path("transactionId");
		String d2 = debit("k3", 20).statusCode(201).extract().path("transactionId");
		String r = refund("k4", d1).statusCode(201).extract().path("transactionId");
		String today = LocalDate.now(ZoneId.of("Asia/Makassar")).toString();

		String joined = List.of(c, d1, d2, r).stream().sorted().map(i -> i + "\n").collect(Collectors.joining());
		String hash = HexFormat.of().formatHex(com.finns.trident.core.Hashes.sha256(joined));
		Fixtures.points().get("/api/v1/points/reconciliation/" + today).then().statusCode(200)
				.header("Cache-Control", "no-store")
				.body("businessDate", equalTo(today))
				.body("timeZone", equalTo("Asia/Makassar"))
				.body("credits.count", equalTo(1)).body("credits.points", equalTo(100))
				.body("debits.count", equalTo(2)).body("debits.points", equalTo(-50))
				.body("refunds.count", equalTo(1)).body("refunds.points", equalTo(30))
				.body("transactionsSha256", equalTo(hash));

		Fixtures.points().get("/api/v1/points/reconciliation/2000-01-01").then().statusCode(200)
				.body("credits.count", equalTo(0)).body("debits.points", equalTo(0))
				.body("transactionsSha256", equalTo(HexFormat.of().formatHex(com.finns.trident.core.Hashes.sha256(""))));
		Fixtures.points().get("/api/v1/points/reconciliation/2026-13-01").then().statusCode(400);
		Fixtures.points().get("/api/v1/points/reconciliation/yesterday").then().statusCode(400);
	}

	@Test
	void theBusinessDateIsBalis() {
		// 23:30 UTC is 07:30 the next day in Bali.
		Instant lateUtc = Instant.parse("2026-10-08T23:30:00Z");
		PointsTxn.Posted posted = QuarkusTransaction.requiringNew().call(() -> PointsTxn.post(PointsTxn.Kind.CREDIT,
				Customer.ofPublicId(customer.publicId).orElseThrow(), 5, null, null, "k1", lateUtc, ZoneId.of("Asia/Makassar")));
		LocalDate date = QuarkusTransaction.requiringNew().call(() -> PointsTxn.<PointsTxn>findById(posted.id()).businessDate);
		assertEquals(LocalDate.parse("2026-10-09"), date);
	}

	@Test
	void protectsEveryPointsPathWithThePointsToken() {
		given().contentType(JSON).get("/api/v1/points/customers/" + id()).then().statusCode(401).body(emptyString());
		given().header("Authorization", "Bearer " + Fixtures.POINTS_TOKEN.replace('t', 'x'))
				.get("/api/v1/points/customers/" + id()).then().statusCode(401);
		// The other APIs' tokens open nothing here, and this token opens nothing there.
		Fixtures.worker().get("/api/v1/points/customers/" + id()).then().statusCode(401);
		Fixtures.gate().get("/api/v1/points/customers/" + id()).then().statusCode(401);
		Fixtures.booking().get("/api/v1/points/customers/" + id()).then().statusCode(401);
		Fixtures.customer(Fixtures.CUSTOMER_SUB).get("/api/v1/points/customers/" + id()).then().statusCode(401);
		Fixtures.points().get("/api/v1/admin/staff-access/x").then().statusCode(401);
		Fixtures.points().body("{\"code\":\"x\"}").post("/api/v1/booking/handoffs/redeem").then().statusCode(401);
		Fixtures.points().body("{\"gateId\":\"g\",\"qr\":\"x\"}").post("/api/v1/gate/check-ins").then().statusCode(401);
		Fixtures.points().post("/api/v1/app/qrs").then().statusCode(401);
		// Unknown paths too, so callers can't probe which endpoints exist.
		given().get("/api/v1/points/nothing-here").then().statusCode(401);
		given().get("/api/v1/points").then().statusCode(401);
		// An unauthenticated posting never reaches the ledger.
		given().contentType(JSON).header("Idempotency-Key", "k1").body(posting(id(), 5)).post("/api/v1/points/credits")
				.then().statusCode(401);
		assertEquals(0, balance());
	}

	private static <T> T get(Future<T> f) {
		try {
			return f.get();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
