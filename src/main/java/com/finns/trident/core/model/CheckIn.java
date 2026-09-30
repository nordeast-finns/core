package com.finns.trident.core.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.GenerationType.IDENTITY;

/** One scan a gate reported, granted or denied. */
@Entity
public class CheckIn extends PanacheEntityBase {
	public enum Result {
		GRANTED,
		DENIED,
	}

	/** Why a check-in was denied. */
	public enum Reason {
		EXPIRED,
		USED,
		/** Well-formed, but no such QR code. */
		UNKNOWN,
		/** Not a FINNS QR code at all. */
		MALFORMED,
	}

	public enum Source {
		/** Core decided while the gate waited. */
		ONLINE,
		/** Reserved: a gate decided alone and uploaded the check-in later. */
		OFFLINE,
	}

	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Long id;

	/** Null when the scan matched no QR code. */
	public UUID qrId;

	@Column(nullable = false)
	public String gateId;

	@Enumerated(STRING)
	@Column(nullable = false)
	public Result result;

	/** Null when granted. */
	@Enumerated(STRING)
	public Reason reason;

	@Enumerated(STRING)
	@Column(nullable = false)
	public Source source;

	/** The gate's clock. */
	@Column(nullable = false)
	public Instant scannedAt;

	/** Core's clock. */
	@Column(nullable = false)
	public Instant recordedAt;

	/** Records an online check-in; {@code reason} null means granted. */
	public static void recordOnline(UUID qrId, String gateId, Reason reason, Instant now) {
		CheckIn c = new CheckIn();
		c.qrId = qrId;
		c.gateId = gateId;
		c.result = reason == null ? Result.GRANTED : Result.DENIED;
		c.reason = reason;
		c.source = Source.ONLINE;
		c.scannedAt = now;
		c.recordedAt = now;
		c.persist();
	}
}
