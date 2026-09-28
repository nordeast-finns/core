package com.finnsbali;

import com.finnsbali.model.Staff;
import com.finnsbali.model.StaffEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

import static com.finnsbali.model.StaffEvent.Action.BOOTSTRAPPED;

/**
 * Makes sure someone can administer staff: while no active ADMIN exists, the configured emails
 * ({@code finns.staff.bootstrap-admins}) become active ADMINs. A no-op once any active ADMIN
 * exists, so it's safe on every startup and on several instances at once.
 */
@ApplicationScoped
public class StaffBootstrap {
	private static final Logger logger = Logger.getLogger(StaffBootstrap.class);

	@Inject
	FinnsConfig config;

	@Transactional
	void onStart(@Observes StartupEvent event) {
		run(config.staff().bootstrapAdmins().orElse(List.of()));
	}

	/** Throws on an invalid email, failing startup rather than silently skipping an admin. */
	@Transactional
	public void run(List<String> emails) {
		Staff.lockAccessChanges();
		if (Staff.countActiveAdmins() > 0) return;
		if (emails.isEmpty()) {
			logger.warn("staff.no_admin: no active ADMIN exists and finns.staff.bootstrap-admins is empty");
			return;
		}

		for (String raw : emails) {
			String email = Staff.normalizeEmail(raw);
			String error = Staff.validateEmail(email);
			if (error != null) throw new IllegalStateException("finns.staff.bootstrap-admins: " + error + ": " + raw);

			Staff staff = Staff.findByEmail(email).orElseGet(() -> {
				Staff s = new Staff();
				s.email = email;
				s.createdBy = Staff.SYSTEM;
				return s;
			});
			staff.role = Staff.Role.ADMIN;
			staff.active = true;
			staff.updatedBy = Staff.SYSTEM;
			staff.persistAndFlush();
			StaffEvent.record(staff, BOOTSTRAPPED, StaffEvent.snapshot(staff), null);
			logger.infof("staff.bootstrapped staffId=%d", staff.id);
		}
	}
}
