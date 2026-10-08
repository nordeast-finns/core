package com.finns.trident.core.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.util.List;
import java.util.Optional;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * A points ledger account: one per customer, created by their first posting, plus the system accounts
 * that hold the other side of every posting. Only {@link PointsTxn} changes balances.
 */
@Entity
public class PointsAccount extends PanacheEntityBase {
	public enum Kind {
		CUSTOMER,
		/** The other side of credits. */
		ISSUED,
		/** The other side of debits. */
		REDEEMED,
	}

	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Long id;

	@Enumerated(STRING)
	@Column(nullable = false)
	public Kind kind;

	/** Null for system accounts. */
	public Long customerId;

	/** Never negative. Null for system accounts, whose balances are summed from their entries. */
	public Long balance;

	/** How many postings the account has had. Null for system accounts. */
	public Long seq;

	public static Optional<PointsAccount> ofCustomer(long customerId) {
		return find("customerId", customerId).firstResultOptional();
	}

	/**
	 * Customer accounts whose cached balance or seq doesn't match their entries: the balance must be
	 * their sum, and seq both their count and the last entry's seq.
	 */
	@SuppressWarnings("unchecked")
	public static List<Long> inconsistent() {
		List<Number> ids = getEntityManager().createNativeQuery("""
				select a.id from points_account a
				left join (
				    select account_id, sum(amount) as total, count(*) as n, max(seq) as last_seq
				    from points_entry where seq is not null group by account_id
				) e on e.account_id = a.id
				where a.kind = 'CUSTOMER'
				  and (a.balance <> coalesce(e.total, 0) or a.seq <> coalesce(e.n, 0) or a.seq <> coalesce(e.last_seq, 0))
				order by a.id""").getResultList();
		return ids.stream().map(Number::longValue).toList();
	}

	/** The sum of every entry in the ledger, which is 0 unless the ledger is broken. */
	public static long entriesTotal() {
		Number total = (Number) getEntityManager()
				.createNativeQuery("select coalesce(sum(amount), 0) from points_entry")
				.getSingleResult();
		return total.longValue();
	}
}
