package com.finns.trident.core.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * A customer of the customer-facing apps. They sign in with Keycloak (realm {@code finns}), which owns
 * their profile. core keeps the subject, so other rows can point at the customer, and a copy of their
 * display name and email for the Admin Console (see {@link Profile}).
 * <p>
 * Every customer is also a points member: there is no separate enrolment (see {@link PointsTxn}).
 */
@Entity
public class Customer extends PanacheEntityBase {
	/**
	 * The display name and email from the customer's verified access token; either may be null. Only a
	 * cache of Keycloak's, refreshed by {@link #ofSubject}, and shown only on the Admin API: never on the
	 * Points or Gate APIs, in the points feed or in logs.
	 */
	public record Profile(String displayName, String email) {
		/** The longest values kept, matching the columns, so an odd claim can't fail the write. */
		public static final int DISPLAY_NAME_MAX = 128;

		public static final int EMAIL_MAX = 320;
	}

	/** A customer with their points balance, which is 0 before their first posting. */
	public record WithBalance(UUID publicId, String displayName, String email, long balance, Instant createdAt) {
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

	/** From {@link Profile}: as fresh as the customer's latest request, and null if the token had none. */
	public String displayName;

	public String email;

	@Column(nullable = false)
	public Instant createdAt;

	/**
	 * The customer with this Keycloak subject, created on their first request, with their profile from
	 * this request's token. Looked up first, since a skipped insert still uses up an id; the insert skips
	 * a row that appeared meanwhile, so two first requests at once can't both create one. The request that
	 * creates it also adds it to the points feed.
	 * <p>
	 * The token is the latest word on the profile, so a claim it lacks clears the copy. The row is only
	 * written when the profile changed.
	 */
	public static Customer ofSubject(String keycloakSub, Profile profile, Instant now) {
		Optional<Customer> existing = find("keycloakSub", keycloakSub).firstResultOptional();
		if (existing.isPresent()) return existing.get().withProfile(profile);
		int created = getEntityManager()
				.createNativeQuery("""
						insert into customer (keycloak_sub, display_name, email, created_at) values (?1, ?2, ?3, ?4)
						on conflict (keycloak_sub) do nothing""")
				.setParameter(1, keycloakSub)
				.setParameter(2, profile.displayName())
				.setParameter(3, profile.email())
				.setParameter(4, now)
				.executeUpdate();
		Customer customer = find("keycloakSub", keycloakSub).singleResult();
		if (created == 1) PointsEvent.customerCreated(customer.id, now);
		return customer.withProfile(profile);
	}

	/** Hibernate writes the row only if this changes it. */
	private Customer withProfile(Profile profile) {
		displayName = profile.displayName();
		email = profile.email();
		return this;
	}

	public static Optional<Customer> ofPublicId(UUID publicId) {
		return find("publicId", publicId).firstResultOptional();
	}

	/** Page {@code page} (0-based) of the customers {@code search} matches, with their balance, newest first. */
	public static List<WithBalance> withBalances(Search search, int page, int size) {
		TypedQuery<Object[]> query = getEntityManager().createQuery("""
				select c.publicId, c.displayName, c.email, coalesce(a.balance, 0L), c.createdAt
				from Customer c left join PointsAccount a on a.customerId = c.id
				""" + where(search) + " order by c.createdAt desc, c.id desc", Object[].class);
		return bind(query, search)
				.setFirstResult(page * size)
				.setMaxResults(size)
				.getResultStream()
				.map(r -> new WithBalance((UUID) r[0], (String) r[1], (String) r[2], ((Number) r[3]).longValue(),
						(Instant) r[4]))
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
