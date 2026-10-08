package com.finns.trident.core;

import com.finns.trident.core.model.Handoff;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

/**
 * Every minute, clears what expired handoff codes carried (the customer's name and email) and deletes
 * old ones. See {@link Handoff#purge}. Every instance runs it; it is idempotent.
 */
@ApplicationScoped
public class HandoffPurge {
	@Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
	void scheduled() {
		run();
	}

	public void run() {
		QuarkusTransaction.requiringNew().run(() -> Handoff.purge(Instant.now()));
	}
}
