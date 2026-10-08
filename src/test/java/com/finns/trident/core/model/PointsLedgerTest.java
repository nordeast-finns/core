package com.finns.trident.core.model;

import com.finns.trident.core.Fixtures;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The invariants Postgres enforces, whatever the Java code does. */
@QuarkusTest
class PointsLedgerTest {
	static final ZoneId BALI = ZoneId.of("Asia/Makassar");

	private Customer customer;

	private PointsTxn.Posted credit;

	@BeforeEach
	void reset() {
		Fixtures.reset();
		customer = Fixtures.customerRow(Fixtures.CUSTOMER_SUB);
		credit = QuarkusTransaction.requiringNew().call(() -> PointsTxn.post(PointsTxn.Kind.CREDIT,
				Customer.ofPublicId(customer.publicId).orElseThrow(), 100, null, null, "k1", Instant.now(), BALI));
	}

	private static void sql(String statement) {
		QuarkusTransaction.requiringNew().run(() -> Panache.getEntityManager().createNativeQuery(statement).executeUpdate());
	}

	@Test
	void postsTwoEntriesThatSumToZero() {
		List<PointsEntry> entries = QuarkusTransaction.requiringNew().call(() -> PointsEntry.<PointsEntry>list("txnId", credit.id()));
		assertEquals(2, entries.size());
		assertEquals(0, entries.stream().mapToLong(e -> e.amount).sum());
		PointsAccount issued = QuarkusTransaction.requiringNew().call(() -> PointsAccount.<PointsAccount>find("kind", PointsAccount.Kind.ISSUED).singleResult());
		assertTrue(entries.stream().anyMatch(e -> e.accountId.equals(issued.id) && e.amount == -100 && e.seq == null));
		// uuidv7: time-ordered, so new transactions land at the end of the primary key index.
		assertEquals(7, credit.id().version());
	}

	@Test
	void transactionsAndEntriesAreAppendOnly() {
		assertThrows(Exception.class, () -> sql("update points_txn set reason = 'x'"));
		assertThrows(Exception.class, () -> sql("delete from points_txn"));
		assertThrows(Exception.class, () -> sql("update points_entry set amount = amount * 2"));
		assertThrows(Exception.class, () -> sql("delete from points_entry"));
	}

	@Test
	void anUnbalancedTransactionCantCommit() {
		UUID id = UUID.randomUUID();
		assertThrows(Exception.class, () -> QuarkusTransaction.requiringNew().run(() -> {
			var em = Panache.getEntityManager();
			em.createNativeQuery("""
					insert into points_txn (id, kind, customer_id, idempotency_key, request_hash, recorded_at, business_date)
					values (?1, 'CREDIT', ?2, 'unbalanced', '\\x00', now(), current_date)""")
					.setParameter(1, id).setParameter(2, customer.id).executeUpdate();
			em.createNativeQuery("insert into points_entry (txn_id, account_id, amount) select ?1, id, 5 from points_account where kind = 'ISSUED'")
					.setParameter(1, id).executeUpdate();
		}));
		assertFalse(QuarkusTransaction.requiringNew().call(() -> PointsTxn.findByIdOptional(id).isPresent()));
	}

	@Test
	void aCustomerBalanceCantGoNegative() {
		assertThrows(Exception.class, () -> sql("update points_account set balance = -1 where customer_id is not null"));
	}
}
