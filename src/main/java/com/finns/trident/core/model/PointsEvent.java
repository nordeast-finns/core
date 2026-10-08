package com.finns.trident.core.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * The feed of new customers and postings that Points API clients read to keep their copy, written in
 * the same transaction as the change.
 * <p>
 * Each row also records its writer's transaction id ({@code xid}, set by the database). Ids are taken
 * in insert order but commit in any order, so a reader paging by id could pass a row that commits
 * later and never see it. {@link #after} therefore serves rows in {@code (xid, id)} order and only from
 * transactions older than every transaction still running, which can't gain rows behind the cursor.
 * A long transaction delays the feed until it ends; it never loses events.
 */
@Entity
public class PointsEvent extends PanacheEntityBase {
	public static final String CUSTOMER_CREATED = "customer.created";

	public static final String POINTS_POSTED = "points.posted";

	private static final Pattern CURSOR = Pattern.compile("([0-9]{1,20})\\.([0-9]{1,19})");

	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Long id;

	@Column(nullable = false)
	public String type;

	@Column(nullable = false)
	public Long customerId;

	/** The posting; null for {@link #CUSTOMER_CREATED}. */
	public UUID txnId;

	@Column(nullable = false)
	public Instant at;

	/** Where a reader is in the feed. {@link #START} is before the first event. */
	public record Cursor(String xid, long id) {
		public static final Cursor START = new Cursor("0", 0);

		/** Opaque to clients. */
		public String encode() {
			return Base64.getUrlEncoder().withoutPadding().encodeToString((xid + "." + id).getBytes(StandardCharsets.US_ASCII));
		}

		/** Empty if {@code text} isn't a cursor this feed issued. */
		public static Optional<Cursor> decode(String text) {
			try {
				String raw = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.US_ASCII);
				Matcher m = CURSOR.matcher(raw);
				// xid8 is an unsigned 64-bit number.
				if (!m.matches() || new BigInteger(m.group(1)).bitLength() > 64) return Optional.empty();
				return Optional.of(new Cursor(m.group(1), Long.parseLong(m.group(2))));
			} catch (IllegalArgumentException e) {
				// Bad base64, or a number out of range.
				return Optional.empty();
			}
		}
	}

	/** An event as the feed serves it; {@code posted} is set for {@link #POINTS_POSTED}. */
	public record Item(long id, String type, UUID customerId, Instant at, PointsTxn.Posted posted) {
	}

	/** {@code next} is the cursor to read on from; the same as the request's when nothing is new. */
	public record Page(List<Item> items, Cursor next) {
	}

	static void customerCreated(long customerId, Instant now) {
		append(CUSTOMER_CREATED, customerId, null, now);
	}

	static void pointsPosted(long customerId, UUID txnId, Instant now) {
		append(POINTS_POSTED, customerId, txnId, now);
	}

	private static void append(String type, long customerId, UUID txnId, Instant now) {
		PointsEvent e = new PointsEvent();
		e.type = type;
		e.customerId = customerId;
		e.txnId = txnId;
		e.at = now;
		e.persist();
	}

	/** Up to {@code limit} events after {@code cursor}, oldest first. */
	public static Page after(Cursor cursor, int limit) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = getEntityManager().createNativeQuery("""
				select ev.id, ev.xid::text, ev.type, c.public_id, ev.at,
				    t.id, t.kind, en.amount, en.balance_after, en.seq, t.reason, t.reference, t.refund_of, t.recorded_at
				from points_event ev
				join customer c on c.id = ev.customer_id
				left join points_txn t on t.id = ev.txn_id
				left join points_entry en on en.txn_id = t.id and en.seq is not null
				where (ev.xid, ev.id) > (cast(?1 as xid8), ?2)
				  and ev.xid < pg_snapshot_xmin(pg_current_snapshot())
				order by ev.xid, ev.id
				limit ?3""")
				.setParameter(1, cursor.xid())
				.setParameter(2, cursor.id())
				.setParameter(3, limit)
				.getResultList();
		List<Item> items = new ArrayList<>(rows.size());
		Cursor next = cursor;
		for (Object[] r : rows) {
			long id = ((Number) r[0]).longValue();
			UUID customerId = (UUID) r[3];
			PointsTxn.Posted posted = r[5] == null ? null : new PointsTxn.Posted((UUID) r[5],
					PointsTxn.Kind.valueOf((String) r[6]), customerId, ((Number) r[7]).longValue(),
					((Number) r[8]).longValue(), ((Number) r[9]).longValue(), (String) r[10], (String) r[11],
					(UUID) r[12], instant(r[13]), false);
			items.add(new Item(id, (String) r[2], customerId, instant(r[4]), posted));
			next = new Cursor((String) r[1], id);
		}
		return new Page(items, next);
	}

	/** Native queries return timestamptz as whichever type the JDBC driver and Hibernate settle on. */
	private static Instant instant(Object value) {
		return switch (value) {
			case Instant i -> i;
			case OffsetDateTime o -> o.toInstant();
			case Timestamp t -> t.toInstant();
			default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
		};
	}
}
