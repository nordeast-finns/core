package com.finns.trident.core;

import com.finns.trident.core.model.Customer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps every customer's copy of their Keycloak name and email the same as Keycloak's. Keycloak's
 * {@code finns-core} extension tells core which user changed ({@link #user}); a reconciliation every
 * {@code finns.keycloak.reconcile-every} compares them all, for any notice that was lost.
 * <p>
 * Keycloak is always read before the transaction that writes, so no database connection waits on it.
 * Running twice at once, here or on another instance, is harmless: {@link Customer#sync} keeps the copy
 * from the latest fetch.
 */
@ApplicationScoped
public class CustomerSync {
	private static final Logger logger = Logger.getLogger(CustomerSync.class);

	/** Users read from Keycloak per request, and synced per transaction, during reconciliation. */
	public static final int PAGE_SIZE = 100;

	/** What one reconciliation looked at: users in Keycloak, and customers it checked one by one. */
	public record Result(int users, int missing) {
	}

	@Inject
	KeycloakUsers keycloak;

	/**
	 * Syncs the Keycloak user with this id, creating the customer if they're new.
	 *
	 * @throws KeycloakUsers.UnavailableException if Keycloak can't say, leaving the copy as it was
	 */
	public void user(String keycloakSub) {
		Instant fetchedAt = Instant.now();
		Optional<KeycloakUsers.User> user = keycloak.user(keycloakSub);
		QuarkusTransaction.requiringNew().run(() -> Customer.sync(keycloakSub, user, fetchedAt, Instant.now()));
	}

	@Scheduled(every = "{finns.keycloak.reconcile-every}", delayed = "1m",
			concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
	void scheduled() {
		try {
			reconcile();
		} catch (KeycloakUsers.UnavailableException e) {
			logger.warnf("keycloak.reconcile_failed reason=%s", e.getMessage());
		}
	}

	/**
	 * Syncs every user in the realm, creating customers that are missing, then checks one by one the
	 * customers it didn't see: deleted from Keycloak, or skipped because users came or went between pages.
	 *
	 * @throws KeycloakUsers.UnavailableException if Keycloak stops answering; what was synced stays
	 */
	public Result reconcile() {
		Instant startedAt = Instant.now();
		Set<String> seen = new HashSet<>();
		for (int first = 0; ; first += PAGE_SIZE) {
			Instant fetchedAt = Instant.now();
			KeycloakUsers.Page page = keycloak.page(first, PAGE_SIZE);
			QuarkusTransaction.requiringNew().run(() -> {
				for (KeycloakUsers.User user : page.users()) {
					Customer.sync(user.id(), Optional.of(user), fetchedAt, Instant.now());
				}
			});
			page.users().forEach(user -> seen.add(user.id()));
			if (page.last()) break;
		}
		// Customers created since the listing started may not be in it, and are being synced anyway.
		List<String> missing = QuarkusTransaction.requiringNew().call(() -> Customer.subjectsMissingFrom(seen, startedAt));
		for (String sub : missing) user(sub);
		logger.infof("keycloak.reconciled users=%d missing=%d", seen.size(), missing.size());
		return new Result(seen.size(), missing.size());
	}
}
