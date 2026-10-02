package com.finns.trident.core.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.Optional;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * A customer of the customer-facing apps. They sign in with Keycloak (realm {@code finns}), which owns
 * their profile; core only keeps the subject, so other rows can point at the customer.
 */
@Entity
public class Customer extends PanacheEntityBase {
	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Long id;

	@Column(nullable = false)
	public String keycloakSub;

	@Column(nullable = false)
	public Instant createdAt;

	/**
	 * The customer with this Keycloak subject, created on their first request. Looked up first, since
	 * a skipped insert still uses up an id; the insert skips a row that appeared meanwhile, so two first
	 * requests at once can't both create one.
	 */
	public static Customer ofSubject(String keycloakSub, Instant now) {
		Optional<Customer> existing = find("keycloakSub", keycloakSub).firstResultOptional();
		if (existing.isPresent()) return existing.get();
		getEntityManager()
				.createNativeQuery("insert into customer (keycloak_sub, created_at) values (?1, ?2) on conflict (keycloak_sub) do nothing")
				.setParameter(1, keycloakSub)
				.setParameter(2, now)
				.executeUpdate();
		return find("keycloakSub", keycloakSub).singleResult();
	}
}
