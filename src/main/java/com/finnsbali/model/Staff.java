package com.finnsbali.model;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * A person allowed into the Admin Console, with their role. Matched by email until their first
 * sign-in links their JumpCloud subject ({@link #link}); the subject is authoritative from then on.
 * <p>
 * {@code @DynamicUpdate} makes an edit write only the columns it changed, so it can't overwrite
 * {@link #jumpcloudSub} or {@link #lastSignInAt}, which sign-in writes without a version bump.
 */
@Entity
@DynamicUpdate
public class Staff extends PanacheEntityBase {
	/** Ordered from least to most privileged. */
	public enum Role {
		STAFF,
		ADMIN,
		;

		/** Exact, case-sensitive match on the name; the wire format is the name. */
		public static Optional<Role> parse(String name) {
			return Arrays.stream(values()).filter(r -> r.name().equals(name)).findFirst();
		}
	}

	public static final int EMAIL_MAX = 254;

	public static final int DISPLAY_NAME_MAX = 200;

	public static final int SUB_MAX = 128;

	/** Written to {@code created_by}/{@code updated_by} and events when no staff member acted. */
	public static final String SYSTEM = "system";

	/** Arbitrary constant naming the advisory lock that serializes access changes. */
	private static final long ACCESS_LOCK_KEY = 0x5354_4146_4600L;

	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Long id;

	/** Normalized: see {@link #normalizeEmail}. */
	@Column(nullable = false)
	public String email;

	public String displayName;

	@Enumerated(STRING)
	@Column(nullable = false)
	public Role role;

	@Column(nullable = false)
	public boolean active = true;

	public String jumpcloudSub;

	public Instant lastSignInAt;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	public Instant createdAt;

	@Column(nullable = false, updatable = false)
	public String createdBy;

	@UpdateTimestamp
	@Column(nullable = false)
	public Instant updatedAt;

	@Column(nullable = false)
	public String updatedBy;

	@Version
	@Column(nullable = false)
	public int version;

	public boolean isActiveAdmin() {
		return active && role == Role.ADMIN;
	}

	public static Optional<Staff> findBySub(String sub) {
		return find("jumpcloudSub", sub).firstResultOptional();
	}

	public static Optional<Staff> findByEmail(String normalizedEmail) {
		return find("email", normalizedEmail).firstResultOptional();
	}

	public static long countActiveAdmins() {
		return count("role = ?1 and active = true", Role.ADMIN);
	}

	/**
	 * Takes the transaction-scoped lock that every access change holds, so invariants such as "at
	 * least one active ADMIN" are checked and written without a concurrent change in between.
	 */
	public static void lockAccessChanges() {
		Panache.getEntityManager()
				.createNativeQuery("select 1 from pg_advisory_xact_lock(?1)")
				.setParameter(1, ACCESS_LOCK_KEY)
				.getSingleResult();
	}

	/**
	 * Links {@code sub} to an active, unlinked row. Returns false when a concurrent change got there
	 * first (a sign-in, unlink, delete or disable), or when {@code sub} is already linked elsewhere.
	 * Doesn't bump {@code version}, so it never makes an admin's open edit stale.
	 */
	public static boolean link(long id, String sub) {
		return update("jumpcloudSub = ?1 where id = ?2 and active = true and jumpcloudSub is null"
				+ " and not exists (select 1 from Staff s where s.jumpcloudSub = ?1)", sub, id) == 1;
	}

	/** Records a sign-in without bumping {@code version}. */
	public static void touchSignIn(long id, Instant at) {
		update("lastSignInAt = ?1 where id = ?2", at, id);
	}

	/** Trims and lowercases. Only ASCII survives {@link #validateEmail}, so the locale can't matter. */
	public static String normalizeEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Checks a normalized email. Returns a field error code ({@code required}, {@code too_long},
	 * {@code invalid_email}), or null when valid. Deliberately pragmatic: JumpCloud owns the real
	 * addresses; this only keeps obvious mistakes and non-ASCII out.
	 */
	public static String validateEmail(String email) {
		if (email.isEmpty()) return "required";
		if (email.length() > EMAIL_MAX) return "too_long";
		for (int i = 0; i < email.length(); i++) {
			char c = email.charAt(i);
			if (c < 0x21 || c > 0x7e) return "invalid_email";
		}
		int at = email.indexOf('@');
		if (at <= 0 || at != email.lastIndexOf('@')) return "invalid_email";
		String domain = email.substring(at + 1);
		if (domain.isEmpty() || domain.startsWith(".") || domain.endsWith(".") || !domain.contains(".")) {
			return "invalid_email";
		}
		return null;
	}
}
