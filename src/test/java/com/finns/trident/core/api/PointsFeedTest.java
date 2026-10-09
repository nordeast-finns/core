package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Customer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PointsFeedTest {
	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	private static JsonPath page(String after, Integer limit) {
		var request = Fixtures.points();
		if (after != null) request.queryParam("after", after);
		if (limit != null) request.queryParam("limit", limit);
		return request.get("/api/v1/points/events").then().statusCode(200).header("Cache-Control", "no-store")
				.extract().jsonPath();
	}

	/** Every event from {@code after} on, reading {@code limit} at a time; the last element is the final cursor. */
	private static List<Map<String, Object>> drain(String after, int limit, List<String> cursor) {
		List<Map<String, Object>> all = new ArrayList<>();
		while (true) {
			JsonPath p = page(after, limit);
			List<Map<String, Object>> events = p.getList("events");
			after = p.getString("next");
			if (events.isEmpty()) break;
			all.addAll(events);
		}
		cursor.add(after);
		return all;
	}

	private static void credit(Customer c, String key, int points) {
		Fixtures.points(key).body(Map.of("customerId", c.publicId.toString(), "points", points, "reference", "ref-" + key))
				.post("/api/v1/points/credits").then().statusCode(201);
	}

	@Test
	void servesNewCustomersAndPostingsInOrder() {
		Customer a = Fixtures.customerRow("sub-a");
		Customer b = Fixtures.customerRow("sub-b");
		// Seeing a customer again adds nothing.
		Fixtures.customerRow("sub-a");
		credit(a, "k1", 10);
		credit(b, "k2", 20);
		String debitId = Fixtures.points("k3").body(Map.of("customerId", a.publicId.toString(), "points", 4))
				.post("/api/v1/points/debits").then().statusCode(201).extract().path("transactionId");
		// Failed postings leave no event.
		Fixtures.points("k4").body(Map.of("customerId", b.publicId.toString(), "points", 400))
				.post("/api/v1/points/debits").then().statusCode(409);

		List<String> cursor = new ArrayList<>();
		List<Map<String, Object>> events = drain(null, 2, cursor);
		assertEquals(List.of("customer.created", "customer.created", "points.posted", "points.posted", "points.posted"),
				events.stream().map(e -> e.get("type")).toList());
		assertEquals(a.publicId.toString(), events.get(0).get("customerId"));
		assertEquals(b.publicId.toString(), events.get(1).get("customerId"));
		assertTrue(events.get(0).get("at") != null && !events.get(0).containsKey("transaction"));

		@SuppressWarnings("unchecked")
		Map<String, Object> last = (Map<String, Object>) events.get(4).get("transaction");
		assertEquals(debitId, last.get("transactionId"));
		assertEquals("debit", last.get("kind"));
		assertEquals(a.publicId.toString(), last.get("customerId"));
		assertEquals(-4, last.get("points"));
		assertEquals(6, last.get("balance"));
		assertEquals(2, last.get("seq"));
		@SuppressWarnings("unchecked")
		Map<String, Object> first = (Map<String, Object>) events.get(2).get("transaction");
		assertEquals("ref-k1", first.get("reference"));

		// Ids are unique and the feed is the same when read again from the start, in any page size.
		assertEquals(5, events.stream().map(e -> e.get("eventId")).distinct().count());
		assertEquals(events, drain(null, 500, new ArrayList<>()));

		// Caught up: an empty page keeps the cursor, and later events follow it.
		String end = cursor.getFirst();
		Fixtures.points().queryParam("after", end).get("/api/v1/points/events").then()
				.body("events.size()", equalTo(0)).body("next", equalTo(end));
		credit(b, "k5", 1);
		JsonPath more = page(end, null);
		assertEquals(1, more.getList("events").size());
		assertEquals("ref-k5", more.getString("events[0].transaction.reference"));
	}

	@Test
	void neverSkipsAnEventThatCommitsLate() throws Exception {
		Customer early = Fixtures.customerRow("sub-early");
		String start = page(null, null).getString("next");

		// A transaction that writes an event first and commits last.
		CountDownLatch written = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> slow = CompletableFuture.runAsync(() -> QuarkusTransaction.requiringNew().run(() -> {
			Customer.ofSubject("sub-slow", new Customer.Profile(null, null), Instant.now());
			written.countDown();
			try {
				assertTrue(release.await(30, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		}));
		assertTrue(written.await(30, TimeUnit.SECONDS));
		try {
			// Commits after the slow one started, with a later event id.
			credit(early, "k1", 5);
			// Serving the credit now would move the cursor past the slow transaction's event.
			JsonPath held = page(start, null);
			assertEquals(0, held.getList("events").size());
			assertEquals(start, held.getString("next"));
		} finally {
			release.countDown();
		}
		slow.get(30, TimeUnit.SECONDS);

		List<Map<String, Object>> events = page(start, null).getList("events");
		assertEquals(List.of("customer.created", "points.posted"), events.stream().map(e -> e.get("type")).toList());
	}

	@Test
	void refusesACursorItDidNotIssue() {
		for (String bad : List.of("nope", "MS4", "LTEuMg", "MTg0NDY3NDQwNzM3MDk1NTE2MTYuMQ", "MS45OTk5OTk5OTk5OTk5OTk5OTk5")) {
			Fixtures.points().queryParam("after", bad).get("/api/v1/points/events").then().statusCode(400)
					.body("code", equalTo("malformed"));
		}
		// An empty cursor reads from the start, and the limit is clamped.
		Fixtures.customerRow("sub-a");
		Fixtures.points().queryParam("after", "").queryParam("limit", 0).get("/api/v1/points/events").then()
				.statusCode(200).body("events.size()", equalTo(1));
		Fixtures.points().queryParam("limit", 100000).get("/api/v1/points/events").then().statusCode(200);
	}
}
