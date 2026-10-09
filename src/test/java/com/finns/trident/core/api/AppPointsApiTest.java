package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsTxn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static com.finns.trident.core.Fixtures.CUSTOMER_SUB;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AppPointsApiTest {
	static final String PATH = "/api/v1/app/points";

	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	@Test
	void aCustomerWithoutPostingsHasZeroPoints() {
		Fixtures.customer(CUSTOMER_SUB).get(PATH).then().statusCode(200)
				.header("Cache-Control", "no-store")
				.body("keySet()", equalTo(Set.of("balance")))
				.body("balance", equalTo(0));
	}

	@Test
	void showsTheSignedInCustomersOwnBalance() {
		Customer customer = Fixtures.customerRow(CUSTOMER_SUB);
		Customer other = Fixtures.customerRow("sub-other");
		earn(customer, 315, PointsTxn.BOOKING_REASON, "b-1");
		earn(customer, 10, PointsTxn.CHECK_IN_REASON, "qr-1");
		earn(other, 99, PointsTxn.BOOKING_REASON, "b-2");

		Fixtures.customer(CUSTOMER_SUB).get(PATH).then().statusCode(200).body("balance", equalTo(325));
	}

	@Test
	void needsTheCustomersToken() {
		given().get(PATH).then().statusCode(401);
		Fixtures.booking().get(PATH).then().statusCode(401);
		Fixtures.points().get(PATH).then().statusCode(401);
	}

	private static void earn(Customer customer, long points, String reason, String reference) {
		QuarkusTransaction.requiringNew().run(() -> PointsTxn.earn(Customer.ofPublicId(customer.publicId).orElseThrow(),
				points, reason, reference, Instant.now(), ZoneId.of("Asia/Makassar")));
	}
}
