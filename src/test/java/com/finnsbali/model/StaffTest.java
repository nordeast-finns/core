package com.finnsbali.model;

import com.finnsbali.Fixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.finnsbali.Fixtures.staff;
import static com.finnsbali.model.Staff.Role.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StaffTest {
	@BeforeEach
	void reset() {
		Fixtures.reset();
	}

	private static boolean link(Staff s, String sub) {
		return QuarkusTransaction.requiringNew().call(() -> Staff.link(s.id, sub));
	}

	@Test
	void linksOnlyAnActiveUnlinkedRow() {
		Staff active = staff("a@x.com", STAFF, true, null);
		Staff disabled = staff("b@x.com", STAFF, false, null);
		Staff linked = staff("c@x.com", STAFF, true, "sub-c");

		assertFalse(link(disabled, "sub-b"), "a row disabled mid-sign-in must not be linked");
		assertFalse(link(linked, "sub-other"));
		assertFalse(link(active, "sub-c"), "a subject is linked to one row at most");
		assertTrue(link(active, "sub-a"));

		assertEquals("sub-a", Fixtures.reload(active.id).jumpcloudSub);
		assertNull(Fixtures.reload(disabled.id).jumpcloudSub);
		assertEquals(active.version, Fixtures.reload(active.id).version);
	}

	@ParameterizedTest
	@CsvSource(nullValues = "NULL", value = {
			"a@b.co, NULL",
			"'', required",
			"no-at, invalid_email",
			"a@b@c.co, invalid_email",
			"@b.co, invalid_email",
			"a@, invalid_email",
			"a@b, invalid_email",
			"a@.b.co, invalid_email",
			"a@b.co., invalid_email",
			"a b@c.co, invalid_email",
			"bü@b.co, invalid_email"})
	void validatesEmails(String email, String expected) {
		assertEquals(expected, Staff.validateEmail(email));
	}

	@Test
	void rejectsOverlongEmails() {
		assertEquals("too_long", Staff.validateEmail("a".repeat(250) + "@b.co"));
		assertNull(Staff.validateEmail("a".repeat(249) + "@b.co"));
	}

	@Test
	void normalizesByTrimmingAndLowercasing() {
		assertEquals("a@b.co", Staff.normalizeEmail("  A@B.Co "));
		assertEquals("", Staff.normalizeEmail(null));
	}
}
