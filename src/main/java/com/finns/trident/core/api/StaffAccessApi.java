package com.finns.trident.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.finns.trident.core.BusinessException;
import com.finns.trident.core.model.Staff;
import com.finns.trident.core.model.StaffEvent;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.finns.trident.core.ErrorCode.MALFORMED;
import static com.finns.trident.core.model.StaffEvent.Action.LINKED;

/**
 * What a JumpCloud subject may do in the Admin Console. The Worker calls {@link #lookup} on every
 * request and {@link #signIn} once per sign-in.
 * <p>
 * Invariant: when {@code signIn} answers {@code active}, {@code lookup} of that subject answers
 * {@code active} too, so the Worker's re-sign-in on a non-active lookup can't loop.
 */
@Path(AdminApiFilter.PREFIX + "/staff-access")
public class StaffAccessApi {
	private static final Logger logger = Logger.getLogger(StaffAccessApi.class);

	@RegisterForReflection
	@JsonInclude(NON_NULL)
	public record Access(Status status, Long staffId, Staff.Role role) {
		public enum Status {
			ACTIVE,
			NONE,
			DISABLED,
			SUB_MISMATCH,
			;

			@JsonValue
			public String wire() {
				return name().toLowerCase(Locale.ROOT);
			}
		}

		static final Access NONE = new Access(Status.NONE, null, null);

		static final Access DISABLED = new Access(Status.DISABLED, null, null);

		static final Access SUB_MISMATCH = new Access(Status.SUB_MISMATCH, null, null);

		static Access of(Staff staff) {
			return staff.active ? new Access(Status.ACTIVE, staff.id, staff.role) : DISABLED;
		}
	}

	@RegisterForReflection
	public record SignIn(String sub, String email) {
	}

	/** Read-only, by linked subject only. */
	@GET
	@Path("{sub}")
	public Access lookup(@RestPath String sub) {
		requireValidSub(sub);
		return Staff.findBySub(sub).map(Access::of).orElse(Access.NONE);
	}

	/**
	 * Matches by linked subject, else by email (linking the subject to an active, unlinked row),
	 * and records the sign-in. An email already linked to another subject is refused: that's a
	 * recreated JumpCloud account, which an admin must unlink first.
	 */
	@POST
	@Path("sign-ins")
	@Transactional
	public Access signIn(SignIn body) {
		if (body == null) throw new BusinessException(MALFORMED);
		String sub = body.sub();
		requireValidSub(sub);
		String email = Staff.normalizeEmail(body.email());

		// A second pass only happens when a concurrent change (sign-in, unlink, delete, disable) hit
		// the row between reading and linking it; that change is committed by the time we re-read.
		for (int attempt = 0; attempt < 2; attempt++) {
			Optional<Staff> bySub = Staff.findBySub(sub);
			if (bySub.isPresent()) return signedIn(bySub.get());

			Optional<Staff> byEmail = email.isEmpty() ? Optional.empty() : Staff.findByEmail(email);
			if (byEmail.isEmpty()) return Access.NONE;

			Staff staff = byEmail.get();
			if (staff.jumpcloudSub != null) {
				logger.warnf("staff.sub_mismatch staffId=%d linkedSub=%s signInSub=%s", staff.id, staff.jumpcloudSub, sub);
				return Access.SUB_MISMATCH;
			}
			// Linking a disabled row would let whoever holds the address now claim it.
			if (!staff.active) return Access.DISABLED;
			if (Staff.link(staff.id, sub)) {
				StaffEvent.record(staff, LINKED, null, null);
				logger.infof("staff.linked staffId=%d", staff.id);
				return signedIn(staff);
			}
			Panache.getEntityManager().clear();
		}
		throw new IllegalStateException("Linking a subject kept racing with concurrent changes");
	}

	private static Access signedIn(Staff staff) {
		if (staff.active) Staff.touchSignIn(staff.id, Instant.now());
		return Access.of(staff);
	}

	private static void requireValidSub(String sub) {
		if (sub == null || sub.isBlank() || sub.length() > Staff.SUB_MAX) throw new BusinessException(MALFORMED);
	}
}
