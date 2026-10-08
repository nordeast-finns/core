package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Customer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.finns.trident.core.Fixtures.as;
import static com.finns.trident.core.Fixtures.staff;
import static com.finns.trident.core.Fixtures.worker;
import static com.finns.trident.core.model.Staff.Role.ADMIN;
import static com.finns.trident.core.model.Staff.Role.STAFF;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CustomerApiTest {
	private static final String PATH = "/api/v1/admin/customers";

	private static final String STAFF_SUB = "sub-staff";

	@BeforeEach
	void reset() {
		Fixtures.reset();
		staff("staff@bla.com", STAFF, true, STAFF_SUB);
	}

	private static void credit(Customer customer, String key, long points) {
		Fixtures.points(key).body(Map.of("customerId", customer.publicId.toString(), "points", points))
				.post("/api/v1/points/credits").then().statusCode(201);
	}

	@Test
	void requiresAnActiveStaffActor() {
		staff("admin@bla.com", ADMIN, true, "sub-admin");
		staff("off@bla.com", STAFF, false, "sub-off");
		worker().get(PATH).then().statusCode(403).body("code", equalTo("forbidden"));
		as("sub-off").get(PATH).then().statusCode(403);
		as("sub-unknown").get(PATH).then().statusCode(403);
		as(STAFF_SUB).get(PATH).then().statusCode(200);
		as("sub-admin").get(PATH).then().statusCode(200);
	}

	@Test
	void listsEveryCustomerWithTheirBalanceNewestFirst() {
		Customer older = Fixtures.customerRow("sub-older");
		Customer newer = Fixtures.customerRow("sub-newer");
		credit(older, "k1", 120);
		credit(older, "k2", 30);

		as(STAFF_SUB).get(PATH).then().statusCode(200)
				.header("Cache-Control", "no-store")
				.body("total", equalTo(2)).body("page", equalTo(1)).body("size", equalTo(50))
				.body("items.customerId", equalTo(List.of(newer.publicId.toString(), older.publicId.toString())))
				.body("items.balance", equalTo(List.of(0, 150)))
				.body("items[0].createdAt", notNullValue())
				// Only the public id: never the internal id or the Keycloak subject.
				.body("items[0].keySet()", equalTo(Set.of("customerId", "balance", "createdAt")));
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
}
