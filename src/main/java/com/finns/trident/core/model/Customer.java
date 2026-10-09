package com.finns.trident.core.model;

import com.finns.trident.core.KeycloakUsers;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * A customer of the customer-facing apps: every user of Keycloak's realm {@code finns}, which owns their
 * account. core keeps the subject, so other rows can point at the customer, and a copy of their display
 * name and email for the Admin Console, kept in sync with Keycloak by {@link #sync} alone.
 * <p>
 * Every customer is also a points member: there is no separate enrolment (see {@link PointsTxn}).
 */
@Entity
public class Customer extends PanacheEntityBase {
	/** A customer with their points balance, which is 0 before their first posting. */
	public record WithBalance(UUID publicId, String displayName, String email, Instant keycloakDeletedAt,
			long balance, Instant createdAt) {
	}

	/**
	 * Which customers the Admin Console lists: the one with {@code publicId}, or those whose lowercased
	 * display name or email matches {@code like}, a LIKE pattern escaped with {@code !}. Both null means
	 * every customer.
	 */
	public record Search(UUID publicId, String like) {
		public static final Search ALL = new Search(null, null);
	}

	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Long id;

	@Column(nullable = false)
	public String keycloakSub;

	/**
	 * The id Points API clients know the customer by. Random, set by the database, and never the
	 * internal id or the Keycloak subject.
	 */
	@Column(nullable = false, insertable = false, updatable = false)
	public UUID publicId;

	/**
	 * Keycloak's, as {@link KeycloakUsers.User} has it; null until the first sync. Shown only on the Admin
	 * API: never on the Points or Gate APIs, in the points feed or in logs. Only {@link #sync} writes them,
	 * hence not insertable or updatable here.
	 */
	@Column(insertable = false, updatable = false)
	public String displayName;

	@Column(insertable = false, updatable = false)
	public String email;

	/** When core found the Keycloak user gone; null while it exists. */
	@Column(insertable = false, updatable = false)
	public Instant keycloakDeletedAt;

	@Column(nullable = false)
	public Instant createdAt;

	/**
	 * The customer with this Keycloak subject, created if it's new: usually by {@link #sync} as they sign
	 * up, else by their first request. Looked up first, since a skipped insert still uses up an id; the
	 * insert skips a row that appeared meanwhile, so two at once can't both create one. Whatever creates it
	 * also adds it to the points feed.
	 */
	public static Customer ofSubject(String keycloakSub, Instant now) {
		Optional<Customer> existing = find("keycloakSub", keycloakSub).firstResultOptional();
		if (existing.isPresent()) return existing.get();
		int created = getEntityManager()
				.createNativeQuery("insert into customer (keycloak_sub, created_at) values (?1, ?2) on conflict (keycloak_sub) do nothing")
				.setParameter(1, keycloakSub)
				.setParameter(2, now)
				.executeUpdate();
		Customer customer = find("keycloakSub", keycloakSub).singleResult();
		if (created == 1) PointsEvent.customerCreated(customer.id, now);
		return customer;
	}

	/**
	 * Makes core's copy of Keycloak user {@code keycloakSub} what a fetch started at {@code fetchedAt}
	 * found: {@code user}, creating the customer if needed, or no user, which clears the copy and marks the
	 * customer deleted (a deleted user never becomes a customer). Lands only if no fetch that started later
	 * has landed already, so fetches finishing out of order can't bring back an older copy: every change in
	 * Keycloak causes a fetch that starts after it. False when it didn't land, or there's no such customer.
	 */
	public static boolean sync(String keycloakSub, Optional<KeycloakUsers.User> user, Instant fetchedAt, Instant now) {
		if (user.isPresent()) {
			ofSubject(keycloakSub, now);
			return getEntityManager().createNativeQuery("""
					update customer set display_name = ?2, email = ?3, keycloak_deleted_at = null, profile_fetched_at = ?4
					where keycloak_sub = ?1 and (profile_fetched_at is null or profile_fetched_at < ?4)""")
					.setParameter(1, keycloakSub)
					.setParameter(2, user.get().displayName())
					.setParameter(3, user.get().email())
					.setParameter(4, fetchedAt)
					.executeUpdate() == 1;
		}
		return getEntityManager().createNativeQuery("""
				update customer set display_name = null, email = null, profile_fetched_at = ?2,
				    keycloak_deleted_at = coalesce(keycloak_deleted_at, ?3)
				where keycloak_sub = ?1 and (profile_fetched_at is null or profile_fetched_at < ?2)""")
				.setParameter(1, keycloakSub)
				.setParameter(2, fetchedAt)
				.setParameter(3, now)
				.executeUpdate() == 1;
	}

	/**
	 * Subjects of the customers created before {@code before} who aren't in {@code present} and aren't
	 * known deleted: candidates for reconciliation to check with Keycloak.
	 */
	public static List<String> subjectsMissingFrom(Set<String> present, Instant before) {
		return getEntityManager().createQuery("""
				select c.keycloakSub from Customer c
				where c.keycloakDeletedAt is null and c.createdAt < :before order by c.id""", String.class)
				.setParameter("before", before)
				.getResultStream()
				.filter(sub -> !present.contains(sub))
				.toList();
	}

	public static Optional<Customer> ofPublicId(UUID publicId) {
		return find("publicId", publicId).firstResultOptional();
	}

	/** Page {@code page} (0-based) of the customers {@code search} matches, with their balance, newest first. */
	public static List<WithBalance> withBalances(Search search, int page, int size) {
		TypedQuery<Object[]> query = getEntityManager().createQuery("""
				select c.publicId, c.displayName, c.email, c.keycloakDeletedAt, coalesce(a.balance, 0L), c.createdAt
				from Customer c left join PointsAccount a on a.customerId = c.id
				""" + where(search) + " order by c.createdAt desc, c.id desc", Object[].class);
		return bind(query, search)
				.setFirstResult(page * size)
				.setMaxResults(size)
				.getResultStream()
				.map(r -> new WithBalance((UUID) r[0], (String) r[1], (String) r[2], (Instant) r[3],
						((Number) r[4]).longValue(), (Instant) r[5]))
				.toList();
	}

	public static long count(Search search) {
		TypedQuery<Long> query = getEntityManager()
				.createQuery("select count(c) from Customer c " + where(search), Long.class);
		return bind(query, search).getSingleResult();
	}

	private static String where(Search search) {
		if (search.publicId() != null) return "where c.publicId = :publicId";
		if (search.like() != null) {
			return "where lower(c.displayName) like :like escape '!' or lower(c.email) like :like escape '!'";
		}
		return "";
	}

	private static <T> TypedQuery<T> bind(TypedQuery<T> query, Search search) {
		if (search.publicId() != null) return query.setParameter("publicId", search.publicId());
		if (search.like() != null) return query.setParameter("like", search.like());
		return query;
	}
}
