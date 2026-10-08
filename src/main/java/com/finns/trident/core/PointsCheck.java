package com.finns.trident.core;

import com.finns.trident.core.model.PointsAccount;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Checks the points ledger daily ({@code finns.points.check-cron}): every customer account's cached
 * balance and seq must match its entries, and all entries must sum to zero. Postgres enforces the
 * rest on every write. A mismatch is logged at ERROR, to alert on; nothing is repaired automatically.
 */
@ApplicationScoped
public class PointsCheck {
	private static final Logger logger = Logger.getLogger(PointsCheck.class);

	/** Arbitrary constant naming the advisory lock that lets one instance run the check. */
	static final long LOCK_KEY = 0x504F_494E_5453L;

	/** {@code ran} is false when another instance held the lock. */
	public record Result(boolean ran, List<Long> inconsistentAccounts, long entriesTotal) {
		public boolean ok() {
			return inconsistentAccounts.isEmpty() && entriesTotal == 0;
		}
	}

	@Scheduled(cron = "{finns.points.check-cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
	void scheduled() {
		run();
	}

	/**
	 * Read-only, so the transaction never gets a transaction id and doesn't hold back the points feed
	 * however long it runs.
	 */
	public Result run() {
		return QuarkusTransaction.requiringNew().call(() -> {
			boolean locked = (Boolean) Panache.getEntityManager()
					.createNativeQuery("select pg_try_advisory_xact_lock(?1)")
					.setParameter(1, LOCK_KEY)
					.getSingleResult();
			if (!locked) {
				logger.info("points.check_skipped: running on another instance");
				return new Result(false, List.of(), 0);
			}
			Result result = new Result(true, PointsAccount.inconsistent(), PointsAccount.entriesTotal());
			for (long id : result.inconsistentAccounts()) logger.errorf("points.check_failed accountId=%d", id);
			if (result.entriesTotal() != 0) logger.errorf("points.check_failed entriesTotal=%d", result.entriesTotal());
			if (result.ok()) logger.info("points.check_ok");
			return result;
		});
	}
}
