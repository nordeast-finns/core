package com.finns.trident.core.model;

import com.finns.trident.core.BusinessException;
import com.finns.trident.core.Hashes;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.finns.trident.core.ErrorCode.ALREADY_REFUNDED;
import static com.finns.trident.core.ErrorCode.IDEMPOTENCY_MISMATCH;
import static com.finns.trident.core.ErrorCode.INSUFFICIENT_POINTS;
import static com.finns.trident.core.ErrorCode.NOT_FOUND;
import static com.finns.trident.core.ErrorCode.NOT_REFUNDABLE;
import static jakarta.persistence.EnumType.STRING;

/**
 * One posting to the points ledger, which is double-entry: a transaction has two {@link PointsEntry}s
 * summing to zero, one on the customer's {@link PointsAccount} and one on a system account. Postgres
 * enforces that, that customer balances never go negative, and that transactions and entries are never
 * changed: a mistake is undone by a refund or another posting.
 * <p>
 * {@link #post} and {@link #refund} are the only writers of the ledger. Each is idempotent on the
 * caller's key: a retry of the same request returns the original transaction instead of posting again.
 */
@Entity
@Immutable
public class PointsTxn extends PanacheEntityBase {
	public enum Kind {
		CREDIT,
		DEBIT,
		/** Reverses a credit or debit in full, once. */
		REFUND,
	}

	@Id
	public UUID id;

	@Enumerated(STRING)
	@Column(nullable = false)
	public Kind kind;

	@Column(nullable = false)
	public Long customerId;

	@Column(nullable = false)
	public String idempotencyKey;

	@Column(nullable = false)
	public byte[] requestHash;

	public String reason;

	public String reference;

	/** The transaction a refund reverses. */
	public UUID refundOf;

	@Column(nullable = false)
	public Instant recordedAt;

	@Column(nullable = false)
	public LocalDate businessDate;

	/**
	 * A transaction as the API returns it. {@code points} is signed, from the customer's side;
	 * {@code balance} and {@code seq} are the customer account's after it.
	 */
	public record Posted(UUID id, Kind kind, UUID customerId, long points, long balance, long seq, String reason,
			String reference, UUID refundOf, Instant recordedAt, boolean replayed) {
	}

	/** A kind's transactions on one business date: how many, and their net effect on balances. */
	public record Totals(long count, long points) {
	}

	/** One business date's transactions, for reconciliation. */
	public record Day(Map<Kind, Totals> totals, String idsSha256) {
	}

	/**
	 * Credits or debits {@code customer}. A debit that would make the balance negative fails with
	 * {@code insufficient_points}. {@code points} is positive; {@code kind} is CREDIT or DEBIT.
	 */
	public static Posted post(Kind kind, Customer customer, long points, String reason, String reference, String key,
			Instant now, ZoneId zone) {
		if (kind == Kind.REFUND || points <= 0) throw new IllegalArgumentException();
		byte[] hash = requestHash(kind.name(), customer.publicId.toString(), Long.toString(points), reason, reference);
		Optional<UUID> claimed = claim(kind, customer.id, key, hash, reason, reference, null, now, zone);
		if (claimed.isEmpty()) {
			return replay(key, hash).orElseThrow(() -> new IllegalStateException("points_txn conflict without its key"));
		}
		UUID id = claimed.get();
		long delta = kind == Kind.CREDIT ? points : -points;
		move(id, customer.id, delta, kind == Kind.CREDIT ? PointsAccount.Kind.ISSUED : PointsAccount.Kind.REDEEMED);
		PointsEvent.pointsPosted(customer.id, id, now);
		return posted(id, false);
	}

	/**
	 * Reverses the credit or debit {@code originalId} in full. Fails with {@code not_found} if there's no
	 * such transaction, {@code not_refundable} for a refund, {@code already_refunded}, and
	 * {@code insufficient_points} when refunding a credit whose points were spent.
	 */
	public static Posted refund(UUID originalId, String reference, String key, Instant now, ZoneId zone) {
		PointsTxn original = PointsTxn.<PointsTxn>findByIdOptional(originalId)
				.orElseThrow(() -> new BusinessException(NOT_FOUND));
		if (original.kind == Kind.REFUND) throw new BusinessException(NOT_REFUNDABLE);
		byte[] hash = requestHash(Kind.REFUND.name(), originalId.toString(), reference);
		Optional<UUID> claimed = claim(Kind.REFUND, original.customerId, key, hash, null, reference, originalId, now, zone);
		if (claimed.isEmpty()) {
			// No transaction has this key, so the conflict was another refund of the same transaction.
			return replay(key, hash).orElseThrow(() -> new BusinessException(ALREADY_REFUNDED));
		}
		UUID id = claimed.get();
		long delta = -PointsEntry.customerSide(originalId).amount;
		move(id, original.customerId, delta,
				original.kind == Kind.CREDIT ? PointsAccount.Kind.ISSUED : PointsAccount.Kind.REDEEMED);
		PointsEvent.pointsPosted(original.customerId, id, now);
		return posted(id, false);
	}

	/**
	 * Inserts the transaction, unless one with the same key, or a refund of the same transaction,
	 * exists. Postgres waits for a conflicting transaction still in flight, so on empty the conflicting
	 * row has committed and is visible.
	 */
	private static Optional<UUID> claim(Kind kind, long customerId, String key, byte[] hash, String reason,
			String reference, UUID refundOf, Instant now, ZoneId zone) {
		@SuppressWarnings("unchecked")
		List<UUID> ids = getEntityManager().createNativeQuery("""
				insert into points_txn (kind, customer_id, idempotency_key, request_hash, reason, reference, refund_of,
				    recorded_at, business_date)
				values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
				on conflict do nothing
				returning id""", UUID.class)
				.setParameter(1, kind.name())
				.setParameter(2, customerId)
				.setParameter(3, key)
				.setParameter(4, hash)
				.setParameter(5, reason)
				.setParameter(6, reference)
				.setParameter(7, refundOf)
				.setParameter(8, now)
				.setParameter(9, LocalDate.ofInstant(now, zone))
				.getResultList();
		return ids.stream().findFirst();
	}

	/** The transaction posted under {@code key}, if any; a different request reusing the key fails. */
	private static Optional<Posted> replay(String key, byte[] hash) {
		Optional<PointsTxn> existing = find("idempotencyKey", key).firstResultOptional();
		if (existing.isEmpty()) return Optional.empty();
		if (!MessageDigest.isEqual(existing.get().requestHash, hash)) throw new BusinessException(IDEMPOTENCY_MISMATCH);
		return Optional.of(posted(existing.get().id, true));
	}

	/**
	 * Moves {@code delta} points onto the customer's account and the opposite onto {@code system}. The
	 * conditional update is the customer's lock and the balance check in one, so concurrent postings for
	 * a customer queue on their row and none can take the balance below zero.
	 */
	private static void move(UUID txnId, long customerId, long delta, PointsAccount.Kind system) {
		var em = getEntityManager();
		em.createNativeQuery("""
				insert into points_account (kind, customer_id, balance, seq) values ('CUSTOMER', ?1, 0, 0)
				on conflict do nothing""")
				.setParameter(1, customerId)
				.executeUpdate();
		@SuppressWarnings("unchecked")
		List<Object[]> moved = em.createNativeQuery("""
				update points_account set balance = balance + ?2, seq = seq + 1
				where customer_id = ?1 and balance + ?2 >= 0
				returning id, balance, seq""")
				.setParameter(1, customerId)
				.setParameter(2, delta)
				.getResultList();
		// Rolls the whole transaction back, including the claimed key, so a retry is evaluated afresh.
		if (moved.isEmpty()) throw new BusinessException(INSUFFICIENT_POINTS);
		Object[] account = moved.getFirst();
		em.createNativeQuery("insert into points_entry (txn_id, account_id, amount, balance_after, seq) values (?1, ?2, ?3, ?4, ?5)")
				.setParameter(1, txnId)
				.setParameter(2, ((Number) account[0]).longValue())
				.setParameter(3, delta)
				.setParameter(4, ((Number) account[1]).longValue())
				.setParameter(5, ((Number) account[2]).longValue())
				.executeUpdate();
		int inserted = em.createNativeQuery("""
				insert into points_entry (txn_id, account_id, amount)
				select ?1, id, ?2 from points_account where kind = ?3 and customer_id is null""")
				.setParameter(1, txnId)
				.setParameter(2, -delta)
				.setParameter(3, system.name())
				.executeUpdate();
		if (inserted != 1) throw new IllegalStateException("points system account missing: " + system);
	}

	/** The transaction {@code id} with its customer side, as the API returns it. */
	static Posted posted(UUID id, boolean replayed) {
		Object[] row = getEntityManager().createQuery("""
				select t, e, c.publicId from PointsTxn t
				join PointsEntry e on e.txnId = t.id and e.seq is not null
				join Customer c on c.id = t.customerId
				where t.id = ?1""", Object[].class)
				.setParameter(1, id)
				.getSingleResult();
		PointsTxn t = (PointsTxn) row[0];
		PointsEntry e = (PointsEntry) row[1];
		return new Posted(t.id, t.kind, (UUID) row[2], e.amount, e.balanceAfter, e.seq, t.reason, t.reference,
				t.refundOf, t.recordedAt, replayed);
	}

	/**
	 * The transactions recorded on business date {@code date}: per kind, the count and net points, and
	 * the SHA-256 of their ids (lowercase, sorted, each followed by a newline) so two parties can compare
	 * the exact set.
	 */
	public static Day day(LocalDate date) {
		Map<Kind, Totals> totals = new EnumMap<>(Kind.class);
		for (Kind kind : Kind.values()) totals.put(kind, new Totals(0, 0));
		List<Object[]> rows = getEntityManager().createQuery("""
				select t.kind, count(t), sum(e.amount) from PointsTxn t
				join PointsEntry e on e.txnId = t.id and e.seq is not null
				where t.businessDate = ?1 group by t.kind""", Object[].class)
				.setParameter(1, date)
				.getResultList();
		for (Object[] r : rows) totals.put((Kind) r[0], new Totals(((Number) r[1]).longValue(), ((Number) r[2]).longValue()));

		List<String> ids = new ArrayList<>(getEntityManager()
				.createQuery("select t.id from PointsTxn t where t.businessDate = ?1", UUID.class)
				.setParameter(1, date)
				.getResultStream()
				.map(UUID::toString)
				.toList());
		ids.sort(null);
		StringBuilder joined = new StringBuilder(ids.size() * 37);
		for (String i : ids) joined.append(i).append('\n');
		return new Day(totals, HexFormat.of().formatHex(Hashes.sha256(joined.toString())));
	}

	/**
	 * What a request asked for, so a retry can be told apart from a different request with the same key.
	 * The parts are joined with NUL, which no part can contain; an absent part is empty, which no present
	 * part can be.
	 */
	private static byte[] requestHash(String... parts) {
		StringBuilder s = new StringBuilder();
		for (String p : parts) s.append(p == null ? "" : p).append('\0');
		return Hashes.sha256(s.toString());
	}
}
