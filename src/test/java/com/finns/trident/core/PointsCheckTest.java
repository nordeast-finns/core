package com.finns.trident.core;

import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsAccount;
import com.finns.trident.core.model.PointsTxn;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PointsCheckTest {
	static final ZoneId BALI = ZoneId.of("Asia/Makassar");

	@Inject
	PointsCheck check;

	private Customer customer;

	@BeforeEach
	void reset() {
		Fixtures.reset();
		customer = Fixtures.customerRow(Fixtures.CUSTOMER_SUB);
		QuarkusTransaction.requiringNew().run(() -> PointsTxn.post(PointsTxn.Kind.CREDIT,
				Customer.ofPublicId(customer.publicId).orElseThrow(), 100, null, null, "k1", Instant.now(), BALI));
	}

	private static void sql(String statement) {
		QuarkusTransaction.requiringNew().run(() -> Panache.getEntityManager().createNativeQuery(statement).executeUpdate());
	}

	@Test
	void theCheckPassesOnAConsistentLedger() {
		QuarkusTransaction.requiringNew().run(() -> {
			PointsTxn.Posted debit = PointsTxn.post(PointsTxn.Kind.DEBIT, Customer.ofPublicId(customer.publicId).orElseThrow(),
					30, null, null, "k2", Instant.now(), BALI);
			PointsTxn.refund(debit.id(), null, "k3", Instant.now(), BALI);
		});
		Fixtures.customerRow("sub-without-postings");
		PointsCheck.Result result = check.run();
		assertTrue(result.ran());
		assertTrue(result.ok(), result.toString());
	}

	@Test
	void theCheckFindsATamperedBalanceOrSeq() {
		PointsAccount account = QuarkusTransaction.requiringNew().call(() -> PointsAccount.ofCustomer(customer.id).orElseThrow());
		sql("update points_account set balance = balance + 1 where customer_id is not null");
		assertEquals(List.of(account.id), check.run().inconsistentAccounts());
		sql("update points_account set balance = balance - 1, seq = seq + 1 where customer_id is not null");
		assertEquals(List.of(account.id), check.run().inconsistentAccounts());
	}

	@Test
	void theCheckRunsOnOneInstanceAtATime() throws Exception {
		var held = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var other = CompletableFuture.runAsync(() -> QuarkusTransaction.requiringNew().run(() -> {
			Panache.getEntityManager().createNativeQuery("select pg_advisory_xact_lock(?1)").setParameter(1, PointsCheck.LOCK_KEY)
					.getSingleResult();
			held.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		}));
		held.await();
		try {
			assertFalse(check.run().ran());
		} finally {
			release.countDown();
		}
		other.get();
		assertTrue(check.run().ran());
	}
}
