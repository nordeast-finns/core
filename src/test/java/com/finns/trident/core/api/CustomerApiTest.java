package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsTxn;
import com.finns.trident.core.model.Staff;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.finns.trident.core.Fixtures.as;
import static com.finns.trident.core.Fixtures.staff;
import static com.finns.trident.core.Fixtures.worker;
import static com.finns.trident.core.model.Staff.Role.ADMIN;
import static com.finns.trident.core.model.Staff.Role.STAFF;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CustomerApiTest {
	private static final String PATH = "/api/v1/admin/customers";

	private static final String STAFF_SUB = "sub-staff";

	private Staff staff;

	@BeforeEach
	void reset() {
		Fixtures.reset();
		staff = staff("staff@bla.com", STAFF, true, STAFF_SUB);
	}

	/** A credit by the points partner, through the Points API. */
	private static void partnerCredit(Customer customer, String key, long points) {
		Fixtures.points(key).body(Map.of("customerId", customer.publicId.toString(), "points", points))
				.post("/api/v1/points/credits").then().statusCode(201);
	}

	private static ValidatableResponse post(String sub, String kind, Customer customer, String key, Object points,
			String reference) {
		Map<String, Object> body = new HashMap<>();
		body.put("points", points);
		body.put("reference", reference);
		return as(sub).header("Idempotency-Key", key).body(body)
				.post(PATH + "/" + customer.publicId + "/" + kind).then();
	}

	private static ValidatableResponse detail(Customer customer) {
		return as(STAFF_SUB).get(PATH + "/" + customer.publicId).then();
	}

	// --- actor ---

	@Test
	void requiresAnActiveStaffActor() {
		Customer customer = Fixtures.customerRow("sub-c");
		staff("admin@bla.com", ADMIN, true, "sub-admin");
		staff("off@bla.com", STAFF, false, "sub-off");
		worker().get(PATH).then().statusCode(403).body("code", equalTo("forbidden"));
		as("sub-off").get(PATH).then().statusCode(403);
		as("sub-unknown").get(PATH).then().statusCode(403);
		as("sub-off").get(PATH + "/" + customer.publicId).then().statusCode(403);
		post("sub-off", "credits", customer, "k1", 10, null).statusCode(403);
		as(STAFF_SUB).get(PATH).then().statusCode(200);
		as("sub-admin").get(PATH).then().statusCode(200);
	}

	// --- list ---

	@Test
	void listsEveryCustomerWithTheirBalanceNewestFirst() {
		Customer older = Fixtures.customerRow("sub-older", "Dewi Lestari", "dewi@example.com");
		Customer newer = Fixtures.customerRow("sub-newer");
		partnerCredit(older, "k1", 120);
		partnerCredit(older, "k2", 30);

		as(STAFF_SUB).get(PATH).then().statusCode(200)
				.header("Cache-Control", "no-store")
				.body("total", equalTo(2)).body("page", equalTo(1)).body("size", equalTo(50))
				.body("items.customerId", equalTo(List.of(newer.publicId.toString(), older.publicId.toString())))
				.body("items.balance", equalTo(List.of(0, 150)))
				.body("items[0].createdAt", notNullValue())
				.body("items.displayName", equalTo(Arrays.asList(null, "Dewi Lestari")))
				.body("items.email", equalTo(Arrays.asList(null, "dewi@example.com")))
				// Only the public id: never the internal id or the Keycloak subject.
				.body("items[0].keySet()", equalTo(Set.of("customerId", "displayName", "email", "balance", "createdAt")));
	}

	@Test
	void searchesNamesAndEmailsIgnoringCase() {
		Customer dewi = Fixtures.customerRow("sub-dewi", "Dewi Lestari", "dewi@example.com");
		Customer budi = Fixtures.customerRow("sub-budi", "Budi", "budi.santoso@mail.example");
		Fixtures.customerRow("sub-none");

		search("lestari").body("total", equalTo(1)).body("items.customerId", equalTo(List.of(dewi.publicId.toString())));
		search("  SANTOSO ").body("items.customerId", equalTo(List.of(budi.publicId.toString())));
		search("example").body("total", equalTo(2))
				.body("items.customerId", equalTo(List.of(budi.publicId.toString(), dewi.publicId.toString())));
		search("nobody").body("total", equalTo(0)).body("items", hasSize(0));
		search("").body("total", equalTo(3));
	}

	@Test
	void searchTreatsWildcardsAsText() {
		Fixtures.customerRow("sub-a", "100% Dewi", "a_b@example.com");
		Fixtures.customerRow("sub-b", "1000 Budi", "ab@example.com");
		search("100%").body("items.displayName", equalTo(List.of("100% Dewi")));
		search("a_b").body("items.displayName", equalTo(List.of("100% Dewi")));
		search("!").body("total", equalTo(0));
	}

	@Test
	void searchFindsACustomerById() {
		Customer dewi = Fixtures.customerRow("sub-dewi", "Dewi", null);
		Fixtures.customerRow("sub-budi", "Budi", null);
		search(dewi.publicId.toString().toUpperCase()).body("total", equalTo(1))
				.body("items.customerId", equalTo(List.of(dewi.publicId.toString())));
		search("0199c3a4-5b6e-7f80-9a1b-2c3d4e5f6a7b").body("total", equalTo(0));
	}

	@Test
	void searchPages() {
		Customer older = Fixtures.customerRow("sub-a", "Dewi A", null);
		Fixtures.customerRow("sub-b", "Dewi B", null);
		Fixtures.customerRow("sub-c", "Budi", null);
		as(STAFF_SUB).queryParam("q", "dewi").queryParam("size", 1).queryParam("page", 2).get(PATH).then()
				.body("total", equalTo(2)).body("items.customerId", equalTo(List.of(older.publicId.toString())));
	}

	private static ValidatableResponse search(String q) {
		return as(STAFF_SUB).queryParam("q", q).get(PATH).then().statusCode(200);
	}

	@Test
	void pages() {
		Customer a = Fixtures.customerRow("sub-a");
		Fixtures.customerRow("sub-b");
		as(STAFF_SUB).queryParam("size", 1).queryParam("page", 2).get(PATH).then()
				.body("total", equalTo(2)).body("items.customerId", equalTo(List.of(a.publicId.toString())));
		as(STAFF_SUB).queryParam("page", 3).get(PATH).then().body("items", hasSize(0));
		as(STAFF_SUB).queryParam("size", 10_000).get(PATH).then().body("size", equalTo(CustomerApi.MAX_PAGE_SIZE));
	}

	// --- detail ---

	@Test
	void detailOfACustomerWithoutPostings() {
		Customer customer = Fixtures.customerRow("sub-c", "Dewi Lestari", "dewi@example.com");
		detail(customer).statusCode(200).header("Cache-Control", "no-store")
				.body("customerId", equalTo(customer.publicId.toString()))
				.body("displayName", equalTo("Dewi Lestari")).body("email", equalTo("dewi@example.com"))
				.body("balance", equalTo(0)).body("seq", equalTo(0))
				.body("createdAt", notNullValue())
				.body("transactions", hasSize(0));
	}

	@Test
	void detailIs404ForAnUnknownCustomer() {
		as(STAFF_SUB).get(PATH + "/0199c3a4-5b6e-7f80-9a1b-2c3d4e5f6a7b").then().statusCode(404)
				.body("code", equalTo("not_found"));
		as(STAFF_SUB).get(PATH + "/not-a-uuid").then().statusCode(404);
	}

	@Test
	void detailListsTransactionsNewestFirstWithWhoPostedThem() {
		Customer customer = Fixtures.customerRow("sub-c");
		partnerCredit(customer, "k1", 100);
		post(STAFF_SUB, "debits", customer, "k2", 40, "TICKET-12").statusCode(201);

		detail(customer).statusCode(200)
				.body("balance", equalTo(60)).body("seq", equalTo(2))
				.body("transactions.kind", equalTo(List.of("debit", "credit")))
				.body("transactions.points", equalTo(List.of(-40, 100)))
				.body("transactions.balance", equalTo(List.of(60, 100)))
				.body("transactions[0].reason", equalTo(PointsTxn.STAFF_REASON))
				.body("transactions[0].reference", equalTo("TICKET-12"))
				.body("transactions[0].staffId", equalTo(staff.id.intValue()))
				.body("transactions[0].staffEmail", equalTo("staff@bla.com"))
				.body("transactions[1].staffId", nullValue())
				.body("transactions[1].staffEmail", nullValue());
	}

	@Test
	void historyKeepsTheStaffIdOfADeletedStaffMember() {
		Customer customer = Fixtures.customerRow("sub-c");
		post(STAFF_SUB, "credits", customer, "k1", 10, null).statusCode(201);
		QuarkusTransaction.requiringNew().run(() -> Staff.deleteById(staff.id));
		staff("other@bla.com", STAFF, true, "sub-other");
		as("sub-other").get(PATH + "/" + customer.publicId).then()
				.body("transactions[0].staffId", equalTo(staff.id.intValue()))
				.body("transactions[0].staffEmail", nullValue());
	}

	// --- credit and debit ---

	@Test
	void staffCreditAndDebit() {
		Customer customer = Fixtures.customerRow("sub-c");
		post(STAFF_SUB, "credits", customer, "k1", 250, null).statusCode(201)
				.header("Cache-Control", "no-store")
				.body("transactionId", notNullValue())
				.body("kind", equalTo("credit")).body("points", equalTo(250)).body("balance", equalTo(250))
				.body("reason", equalTo(PointsTxn.STAFF_REASON)).body("reference", nullValue())
				.body("staffEmail", equalTo("staff@bla.com"));
		post(STAFF_SUB, "debits", customer, "k2", 50, "R-1").statusCode(201)
				.body("kind", equalTo("debit")).body("points", equalTo(-50)).body("balance", equalTo(200));
		detail(customer).body("balance", equalTo(200));
	}

	@Test
	void aDebitCantTakeTheBalanceBelowZero() {
		Customer customer = Fixtures.customerRow("sub-c");
		post(STAFF_SUB, "credits", customer, "k1", 10, null).statusCode(201);
		post(STAFF_SUB, "debits", customer, "k2", 11, null).statusCode(409)
				.body("code", equalTo("insufficient_points"));
		detail(customer).body("balance", equalTo(10)).body("transactions", hasSize(1));
	}

	@Test
	void aRetryReplaysInsteadOfPostingTwice() {
		Customer customer = Fixtures.customerRow("sub-c");
		String id = post(STAFF_SUB, "credits", customer, "k1", 10, null).statusCode(201)
				.extract().path("transactionId");
		post(STAFF_SUB, "credits", customer, "k1", 10, null).statusCode(201)
				.header("Idempotent-Replayed", "true").body("transactionId", equalTo(id));
		post(STAFF_SUB, "credits", customer, "k1", 11, null).statusCode(422)
				.body("code", equalTo("idempotency_mismatch"));
		detail(customer).body("balance", equalTo(10));
	}

	@Test
	void keysAreScopedPerClient() {
		Customer customer = Fixtures.customerRow("sub-c");
		staff("other@bla.com", STAFF, true, "sub-other");
		partnerCredit(customer, "same-key", 1);
		post(STAFF_SUB, "credits", customer, "same-key", 2, null).statusCode(201).body("balance", equalTo(3));
		post("sub-other", "credits", customer, "same-key", 4, null).statusCode(201).body("balance", equalTo(7));
		// And the partner's retry still replays its own posting.
		Fixtures.points("same-key").body(Map.of("customerId", customer.publicId.toString(), "points", 1))
				.post("/api/v1/points/credits").then().statusCode(201).header("Idempotent-Replayed", "true")
				.body("balance", equalTo(1));
	}

	@Test
	void validatesThePosting() {
		Customer customer = Fixtures.customerRow("sub-c");
		post(STAFF_SUB, "credits", customer, "k1", 0, "has space").statusCode(422)
				.body("code", equalTo("invalid"))
				.body("fields.points", equalTo("invalid"))
				.body("fields.reference", equalTo("invalid"));
		post(STAFF_SUB, "credits", customer, "k1", null, null).statusCode(422).body("fields.points", equalTo("required"));
		post(STAFF_SUB, "credits", customer, "k1", 1.5, null).statusCode(422).body("fields.points", equalTo("invalid"));
		post(STAFF_SUB, "credits", customer, "k1", "10", null).statusCode(422).body("fields.points", equalTo("invalid"));
		as(STAFF_SUB).body(Map.of("points", 10)).post(PATH + "/" + customer.publicId + "/credits").then()
				.statusCode(400).body("code", equalTo("malformed"));
		as(STAFF_SUB).header("Idempotency-Key", "k1").body(Map.of("points", 10))
				.post(PATH + "/0199c3a4-5b6e-7f80-9a1b-2c3d4e5f6a7b/credits").then().statusCode(404);
	}

	@Test
	void thePartnerCantRefundAStaffPosting() {
		Customer customer = Fixtures.customerRow("sub-c");
		String id = post(STAFF_SUB, "credits", customer, "k1", 10, null).statusCode(201)
				.extract().path("transactionId");
		Fixtures.points("k2").body("{}").post("/api/v1/points/transactions/" + id + "/refund").then()
				.statusCode(409).body("code", equalTo("not_refundable"));
		detail(customer).body("balance", equalTo(10));
	}

	@Test
	void staffPostingsReachThePartnersFeed() {
		Customer customer = Fixtures.customerRow("sub-c");
		post(STAFF_SUB, "credits", customer, "k1", 10, null).statusCode(201);
		Fixtures.points().get("/api/v1/points/events").then().statusCode(200)
				.body("events.transaction.reason", hasItem(PointsTxn.STAFF_REASON));
	}
}
