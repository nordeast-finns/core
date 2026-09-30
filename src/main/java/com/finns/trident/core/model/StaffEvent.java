package com.finns.trident.core.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.GenerationType.IDENTITY;

/** One change to a staff member's access. Written in the same transaction as the change. */
@Entity
public class StaffEvent extends PanacheEntityBase {
	public enum Action {
		CREATED,
		UPDATED,
		DELETED,
		UNLINKED,
		LINKED,
		BOOTSTRAPPED,
	}

	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Long id;

	@Column(nullable = false)
	public long staffId;

	/** The staff member's email when the event happened. */
	@Column(nullable = false)
	public String staffEmail;

	@Enumerated(STRING)
	@Column(nullable = false)
	public Action action;

	/**
	 * CREATED/DELETED/BOOTSTRAPPED: a snapshot of the access fields. UPDATED: each changed field
	 * as {@code [old, new]}. Otherwise null.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	public Map<String, Object> changes;

	/** Null for system events. */
	public Long actorId;

	@Column(nullable = false)
	public String actorEmail;

	@Column(nullable = false)
	public Instant at;

	/** {@code actor} is null for system events. */
	public static void record(Staff staff, Action action, Map<String, Object> changes, Staff actor) {
		StaffEvent e = new StaffEvent();
		e.staffId = staff.id;
		e.staffEmail = staff.email;
		e.action = action;
		e.changes = changes;
		e.actorId = actor == null ? null : actor.id;
		e.actorEmail = actor == null ? Staff.SYSTEM : actor.email;
		e.at = Instant.now();
		e.persist();
	}

	/** The access fields of {@code staff}, for CREATED/DELETED/BOOTSTRAPPED events. */
	public static Map<String, Object> snapshot(Staff staff) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("email", staff.email);
		snapshot.put("displayName", staff.displayName);
		snapshot.put("role", staff.role.name());
		snapshot.put("active", staff.active);
		return snapshot;
	}

	public static List<StaffEvent> latestFor(long staffId, int limit) {
		return find("staffId = ?1 order by at desc, id desc", staffId).page(0, limit).list();
	}
}
