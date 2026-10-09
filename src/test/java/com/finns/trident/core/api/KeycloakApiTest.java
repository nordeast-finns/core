package com.finns.trident.core.api;

import com.finns.trident.core.CustomerSync;
import com.finns.trident.core.FakeKeycloak;
import com.finns.trident.core.Fixtures;
import com.finns.trident.core.KeycloakUsers;
import com.finns.trident.core.model.Customer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.finns.trident.core.Fixtures.CUSTOMER_SUB;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keycloak's notices, the sync behind them and reconciliation, against {@link FakeKeycloak}. */
@QuarkusTest
class KeycloakApiTest {
	private static final String PATH = "/api/v1/keycloak/user-changes";

	@Inject
	CustomerSync sync;

	@BeforeEach
	void reset() {
		Fixtures.reset();
		FakeKeycloak.reset();
	}

	private static ValidatableResponse changed(String userId) {
		return Fixtures.keycloak().body(Map.of("userId", userId)).post(PATH).then();
	}

	private static List<String> feedTypes() {
		return Fixtures.points().get("/api/v1/points/events").then().statusCode(200).extract().path("events.type");
	}

	// --- notices ---

	@Test
	void aSignUpCreatesTheCustomerWithTheirNameAndEmail() {
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", "Lestari", "dewi@example.com");
		changed(CUSTOMER_SUB).statusCode(204).body(emptyString());

		Customer customer = Fixtures.findCustomer(CUSTOMER_SUB);
		assertEquals("Dewi Lestari", customer.displayName);
		assertEquals("dewi@example.com", customer.email);
		assertNull(customer.keycloakDeletedAt);
		// A member of the points program from sign-up, without any personal data in the feed.
		assertEquals(List.of("customer.created"), feedTypes());
	}

	@Test
	void everyChangeIsCopiedAsItIsInKeycloak() {
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", "Lestari", "dewi@example.com");
		changed(CUSTOMER_SUB).statusCode(204);
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", "Santoso", "dewi.santoso@example.com");
		changed(CUSTOMER_SUB).statusCode(204);

		Customer customer = Fixtures.findCustomer(CUSTOMER_SUB);
		assertEquals("Dewi Santoso", customer.displayName);
		assertEquals("dewi.santoso@example.com", customer.email);
		// Created once.
		assertEquals(List.of("customer.created"), feedTypes());
	}

	@Test
	void buildsTheNameAsKeycloaksNameClaimDoes() {
		FakeKeycloak.user("only-first", "Dewi", null, null);
		FakeKeycloak.user("only-last", null, "Lestari", null);
		FakeKeycloak.user("neither", null, null, "x@example.com");
		FakeKeycloak.user("spaces", " Dewi ", "  ", null);
		FakeKeycloak.user("long", "a".repeat(255), "b".repeat(255), "c".repeat(243) + "@example.com");
		for (String id : List.of("only-first", "only-last", "neither", "spaces", "long")) changed(id).statusCode(204);

		assertEquals("Dewi", Fixtures.findCustomer("only-first").displayName);
		assertEquals("Lestari", Fixtures.findCustomer("only-last").displayName);
		assertNull(Fixtures.findCustomer("neither").displayName);
		assertNull(Fixtures.findCustomer("only-first").email);
		// Copied exactly, never trimmed or cut.
		assertEquals(" Dewi    ", Fixtures.findCustomer("spaces").displayName);
		assertEquals("a".repeat(255) + " " + "b".repeat(255), Fixtures.findCustomer("long").displayName);
		assertEquals(255, Fixtures.findCustomer("long").email.length());
	}

	@Test
	void aDeletedUserKeepsTheCustomerWithoutTheirNameOrEmail() {
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", "Lestari", "dewi@example.com");
		changed(CUSTOMER_SUB).statusCode(204);
		FakeKeycloak.delete(CUSTOMER_SUB);
		changed(CUSTOMER_SUB).statusCode(204);

		Customer customer = Fixtures.findCustomer(CUSTOMER_SUB);
		assertNull(customer.displayName);
		assertNull(customer.email);
		assertNotNull(customer.keycloakDeletedAt);
		Fixtures.staff("staff@bla.com", com.finns.trident.core.model.Staff.Role.STAFF, true, "sub-staff");
		Fixtures.as("sub-staff").get("/api/v1/admin/customers/" + customer.publicId).then().statusCode(200)
				.body("deletedAt", notNullValue()).body("displayName", nullValue()).body("email", nullValue());

		// A later notice for the deleted user keeps the first deletion time.
		changed(CUSTOMER_SUB).statusCode(204);
		assertEquals(customer.keycloakDeletedAt, Fixtures.findCustomer(CUSTOMER_SUB).keycloakDeletedAt);
	}

	@Test
	void aDeletedOrUnknownUserNeverBecomesACustomer() {
		changed("never-existed").statusCode(204);
		FakeKeycloak.serviceAccount("service-account-1");
		changed("service-account-1").statusCode(204);
		assertTrue(Fixtures.customers().isEmpty());
		assertTrue(feedTypes().isEmpty());
	}

	@Test
	void aRequestNeverUndoesTheCopy() {
		// Signing out, issuing QR and handoff codes: none of them touch what Keycloak said.
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", "Lestari", "dewi@example.com");
		changed(CUSTOMER_SUB).statusCode(204);
		String staleToken = Fixtures.customerToken(CUSTOMER_SUB, c -> c.claim("name", "Old").claim("sid", "s1"));
		given().auth().oauth2(staleToken).post("/api/v1/app/qrs").then().statusCode(201);
		given().auth().oauth2(staleToken).post("/api/v1/app/handoffs").then().statusCode(201);
		given().auth().oauth2(staleToken).post("/api/v1/app/logout").then().statusCode(204);

		Customer customer = Fixtures.findCustomer(CUSTOMER_SUB);
		assertEquals("Dewi Lestari", customer.displayName);
		assertEquals("dewi@example.com", customer.email);
	}

	@Test
	void anOlderFetchNeverOverwritesANewerOne() {
		Instant older = Instant.parse("2026-10-09T10:00:00Z");
		Instant newer = older.plusMillis(1);
		QuarkusTransaction.requiringNew().run(() -> {
			assertTrue(Customer.sync(CUSTOMER_SUB, Optional.of(new KeycloakUsers.User(CUSTOMER_SUB, "New", "new@example.com")), newer, newer));
			assertFalse(Customer.sync(CUSTOMER_SUB, Optional.of(new KeycloakUsers.User(CUSTOMER_SUB, "Old", "old@example.com")), older, newer));
			assertFalse(Customer.sync(CUSTOMER_SUB, Optional.empty(), older, newer));
			// Nor one that started at the same instant.
			assertFalse(Customer.sync(CUSTOMER_SUB, Optional.empty(), newer, newer));
		});
		Customer customer = Fixtures.findCustomer(CUSTOMER_SUB);
		assertEquals("New", customer.displayName);
		assertNull(customer.keycloakDeletedAt);
	}

	@Test
	void aUserBackFromDeletionIsNoLongerMarkedDeleted() {
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", null, null);
		changed(CUSTOMER_SUB).statusCode(204);
		FakeKeycloak.delete(CUSTOMER_SUB);
		changed(CUSTOMER_SUB).statusCode(204);
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", null, null);
		changed(CUSTOMER_SUB).statusCode(204);
		assertNull(Fixtures.findCustomer(CUSTOMER_SUB).keycloakDeletedAt);
		assertEquals("Dewi", Fixtures.findCustomer(CUSTOMER_SUB).displayName);
	}

	// --- Keycloak's Admin API ---

	@Test
	void reusesItsAccessTokenAndRenewsARevokedOne() {
		FakeKeycloak.user("a", "A", null, null);
		FakeKeycloak.user("b", "B", null, null);
		changed("a").statusCode(204);
		changed("b").statusCode(204);
		assertEquals(1, FakeKeycloak.tokensIssued());

		FakeKeycloak.revokeToken();
		changed("a").statusCode(204);
		assertEquals(2, FakeKeycloak.tokensIssued());
	}

	@Test
	void whenKeycloakFailsTheCopyStaysAndTheExtensionRetries() {
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", null, null);
		changed(CUSTOMER_SUB).statusCode(204);
		FakeKeycloak.user(CUSTOMER_SUB, "Changed", null, null);
		FakeKeycloak.failAdmin(500);
		changed(CUSTOMER_SUB).statusCode(503).body("code", equalTo("unavailable"));
		assertEquals("Dewi", Fixtures.findCustomer(CUSTOMER_SUB).displayName);

		// The extension's retry.
		changed(CUSTOMER_SUB).statusCode(204);
		assertEquals("Changed", Fixtures.findCustomer(CUSTOMER_SUB).displayName);
	}

	@Test
	void encodesTheUserIdInThePath() {
		String id = "f:ldap-1:dewi lestari/1?x=%";
		FakeKeycloak.user(id, "Dewi", null, null);
		changed(id).statusCode(400);
		String federated = "f:ldap-1:dewi.lestari%40x?y#z/1";
		FakeKeycloak.user(federated, "Dewi", null, null);
		changed(federated).statusCode(204);
		assertEquals("Dewi", Fixtures.findCustomer(federated).displayName);
	}

	// --- the API itself ---

	@Test
	void needsTheExtensionsToken() {
		given().contentType(JSON).body(Map.of("userId", "a")).post(PATH).then().statusCode(401).body(emptyString());
		given().header("Authorization", "Bearer wrong").contentType(JSON).body(Map.of("userId", "a")).post(PATH).then()
				.statusCode(401);
		Fixtures.worker().body(Map.of("userId", "a")).post(PATH).then().statusCode(401);
		Fixtures.points().body(Map.of("userId", "a")).post(PATH).then().statusCode(401);
		// And its token opens nothing else.
		given().header("Authorization", "Bearer " + Fixtures.KEYCLOAK_TOKEN).get("/api/v1/admin/customers").then().statusCode(401);
		Fixtures.keycloak().get("/api/v1/keycloak/unknown").then().statusCode(404);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"userId\":null}", "{\"userId\":\"\"}", "{\"userId\":\"has space\"}", "{\"userId\":7}"})
	void refusesAMalformedNotice(String body) {
		Fixtures.keycloak().body(body).post(PATH).then().statusCode(400).body("code", equalTo("malformed"));
	}

	@Test
	void refusesAnOverlongId() {
		Fixtures.keycloak().body(Map.of("userId", "x".repeat(129))).post(PATH).then().statusCode(400);
	}

	// --- reconciliation ---

	@Test
	void reconciliationCopiesEveryUserAndMarksTheDeleted() {
		Fixtures.customerRow("before-sync");
		FakeKeycloak.user("before-sync", "Existing", "Customer", "existing@example.com");
		FakeKeycloak.user("new-user", "New", null, "new@example.com");
		FakeKeycloak.serviceAccount("service-account-1");
		Fixtures.customerRow("gone", "Gone", "gone@example.com");

		CustomerSync.Result result = sync.reconcile();
		assertEquals(2, result.users());
		assertEquals(1, result.missing());

		assertEquals("Existing Customer", Fixtures.findCustomer("before-sync").displayName);
		assertEquals("New", Fixtures.findCustomer("new-user").displayName);
		Customer gone = Fixtures.findCustomer("gone");
		assertNull(gone.displayName);
		assertNotNull(gone.keycloakDeletedAt);
		assertEquals(Set.of("before-sync", "new-user", "gone"),
				Fixtures.customers().stream().map(c -> c.keycloakSub).collect(Collectors.toSet()));

		// Done: the next run checks no one one by one.
		assertEquals(0, sync.reconcile().missing());
	}

	@Test
	void reconciliationPagesThroughEveryUser() {
		for (int i = 0; i < CustomerSync.PAGE_SIZE * 2 + 1; i++) FakeKeycloak.user("user-" + i, "User " + i, null, null);
		assertEquals(CustomerSync.PAGE_SIZE * 2 + 1, sync.reconcile().users());
		assertEquals(CustomerSync.PAGE_SIZE * 2 + 1, Fixtures.customers().size());
	}

	@Test
	void reconciliationChecksACustomerItDidntSeeBeforeMarkingThemDeleted() {
		// Users that came or went between pages can push someone off the listing.
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", null, null);
		Fixtures.customerRow(CUSTOMER_SUB);
		FakeKeycloak.unlist(CUSTOMER_SUB);

		assertEquals(1, sync.reconcile().missing());
		Customer customer = Fixtures.findCustomer(CUSTOMER_SUB);
		assertNull(customer.keycloakDeletedAt);
		assertEquals("Dewi", customer.displayName);
	}

	@Test
	void reconciliationStopsWhenKeycloakFailsAndKeepsWhatItSynced() {
		FakeKeycloak.user(CUSTOMER_SUB, "Dewi", null, null);
		Fixtures.customerRow("other", "Other", null);
		FakeKeycloak.failAdmin(503);
		assertThrows(KeycloakUsers.UnavailableException.class, () -> sync.reconcile());
		// Nothing marked deleted on a failed listing.
		assertNull(Fixtures.findCustomer("other").keycloakDeletedAt);
	}
}
