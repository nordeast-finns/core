package com.finns.trident.core.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * A customer of the customer-facing apps. They sign in with Keycloak (realm {@code finns}), which owns
 * their profile; core only keeps the subject, so other rows can point at the customer.
 * <p>
 * Every customer is also a points member: there is no separate enrolment (see {@link PointsTxn}).
 */
@Entity
public class Customer extends PanacheEntityBase {
	/** A customer with their points balance, which is 0 before their first posting. */
	public record WithBalance(UUID publicId, long balance, Instant createdAt) {
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

	@Column(nullable = false)
	public Instant createdAt;

	/**
	 * The customer with this Keycloak subject, created on their first request. Looked up first, since
	 * a skipped insert still uses up an id; the insert skips a row that appeared meanwhile, so two first
	 * requests at once can't both create one. The request that creates it also adds it to the points feed.
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

	public static Optional<Customer> ofPublicId(UUID publicId) {
		return find("publicId", publicId).firstResultOptional();
	}

	/** Page {@code page} (0-based) of every customer with their balance, newest first. */
	public static List<WithBalance> withBalances(int page, int size) {
		return getEntityManager().createQuery("""
				select c.publicId, coalesce(a.balance, 0L), c.createdAt
				from Customer c left join PointsAccount a on a.customerId = c.id
				order by c.createdAt desc, c.id desc""", Object[].class)
				.setFirstResult(page * size)
				.setMaxResults(size)
				.getResultStream()
				.map(r -> new WithBalance((UUID) r[0], ((Number) r[1]).longValue(), (Instant) r[2]))
				.toList();
	}
}
