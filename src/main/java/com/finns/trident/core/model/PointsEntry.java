package com.finns.trident.core.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * One signed amount on one account within a {@link PointsTxn}. A transaction's entries sum to zero.
 * Written only by {@link PointsTxn}, never changed.
 */
@Entity
@Immutable
public class PointsEntry extends PanacheEntityBase {
	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Long id;

	@Column(nullable = false)
	public UUID txnId;

	@Column(nullable = false)
	public Long accountId;

	@Column(nullable = false)
	public Long amount;

	/** The customer account's balance after this entry. Null on system accounts. */
	public Long balanceAfter;

	/** The customer account's seq after this entry. Null on system accounts. */
	public Long seq;

	/** The customer's side of a transaction. */
	static PointsEntry customerSide(UUID txnId) {
		return find("txnId = ?1 and seq is not null", txnId).singleResult();
	}
}
