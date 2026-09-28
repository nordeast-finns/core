package com.finnsbali;

import com.finnsbali.model.Staff;
import com.finnsbali.model.StaffEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.finnsbali.Fixtures.staff;
import static com.finnsbali.model.Staff.Role.ADMIN;
import static com.finnsbali.model.Staff.Role.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StaffBootstrapTest {
	@Inject
	StaffBootstrap bootstrap;

	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	private static List<Staff> all() {
		return QuarkusTransaction.requiringNew().call(() -> Staff.<Staff>listAll());
	}

	@Test
	void createsAdminsWhenNoneExist() {
		bootstrap.run(List.of(" Thomas@Nordeast.id "));
		Staff s = all().getFirst();
		assertEquals("thomas@nordeast.id", s.email);
		assertTrue(s.isActiveAdmin());
		assertEquals(Staff.SYSTEM, s.createdBy);
		assertEquals(StaffEvent.Action.BOOTSTRAPPED, Fixtures.events(s.id).getFirst().action);
	}

	@Test
	void promotesAndReactivatesAnExistingRow() {
		Staff existing = staff("thomas@nordeast.id", STAFF, false, "sub-t");
		bootstrap.run(List.of("thomas@nordeast.id"));
		Staff after = Fixtures.reload(existing.id);
		assertTrue(after.isActiveAdmin());
		assertEquals("sub-t", after.jumpcloudSub);
	}

	@Test
	void doesNothingOnceAnActiveAdminExists() {
		staff("admin@bla.com", ADMIN, true, null);
		bootstrap.run(List.of("thomas@nordeast.id"));
		assertEquals(1, all().size());
	}

	@Test
	void failsOnAnInvalidEmail() {
		assertThrows(IllegalStateException.class, () -> bootstrap.run(List.of("not-an-email")));
		assertTrue(all().isEmpty());
	}

	@Test
	void concurrentRunsCreateOneRow() throws Exception {
		CompletableFuture<Void> a = CompletableFuture.runAsync(() -> bootstrap.run(List.of("thomas@nordeast.id")));
		CompletableFuture<Void> b = CompletableFuture.runAsync(() -> bootstrap.run(List.of("thomas@nordeast.id")));
		CompletableFuture.allOf(a, b).get();
		assertEquals(1, all().size());
	}
}
