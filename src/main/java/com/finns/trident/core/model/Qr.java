package com.finns.trident.core.model;

import com.finns.trident.core.Hashes;
import com.finns.trident.core.model.CheckIn.Reason;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * A one-time check-in QR code, issued to one customer. The customer app shows {@link Issued#qr} as a
 * QR code; a gate sends it back and {@link #consume} decides. Only the token's hash is stored.
 * <p>
 * The QR text is versioned by its prefix: {@value #V1_PREFIX} is an opaque token that only core can
 * check. A later version (such as a signed QR code a gate can verify offline) gets its own prefix, so
 * deployed gates can tell them apart. Clients never parse the text beyond the prefix.
 */
@Entity
public class Qr extends PanacheEntityBase {
	public static final String V1_PREFIX = "FINNS1:";

	static final int TOKEN_BYTES = 32;

	/** Unpadded base64url length of {@link #TOKEN_BYTES}. */
	static final int TOKEN_CHARS = 43;

	/** Generated here rather than by the database, so a future signed QR code can carry its own id. */
	@Id
	public UUID id;

	@Column(nullable = false)
	public Long customerId;

	@Column(nullable = false)
	public byte[] tokenHash;

	@Column(nullable = false)
	public Instant issuedAt;

	@Column(nullable = false)
	public Instant expiresAt;

	public Instant usedAt;

	public String usedGate;

	/** A freshly issued QR code. {@code qr} holds the raw token: return it to the client, never log it. */
	public record Issued(UUID id, String qr, Instant expiresAt) {
	}

	/** {@code qrId} and {@code customerId} are null when the token matches no QR code. */
	public record Outcome(Reason denied, UUID qrId, Long customerId) {
		public boolean granted() {
			return denied == null;
		}
	}

	public static Issued issue(long customerId, Duration ttl, Instant now) {
		byte[] token = new byte[TOKEN_BYTES];
		// Not a static field: native images initialise classes at build time, which would bake the
		// seed into the image.
		new SecureRandom().nextBytes(token);

		Qr qr = new Qr();
		qr.id = UUID.randomUUID();
		qr.customerId = customerId;
		qr.tokenHash = Hashes.sha256(token);
		qr.issuedAt = now;
		qr.expiresAt = now.plus(ttl);
		qr.persist();
		return new Issued(qr.id, encode(token), qr.expiresAt);
	}

	/**
	 * Marks the QR code used if it's unused and unexpired. One conditional update, so two gates
	 * scanning the same QR code at once can't both be granted.
	 */
	public static Outcome consume(byte[] token, String gateId, Instant now) {
		byte[] hash = Hashes.sha256(token);
		int updated = update("usedAt = ?1, usedGate = ?2 where tokenHash = ?3 and usedAt is null and expiresAt > ?1",
				now, gateId, hash);
		Optional<Qr> found = find("tokenHash", hash).firstResultOptional();
		if (found.isEmpty()) return new Outcome(Reason.UNKNOWN, null, null);
		Qr qr = found.get();
		if (updated == 1) return new Outcome(null, qr.id, qr.customerId);
		return new Outcome(qr.usedAt != null ? Reason.USED : Reason.EXPIRED, qr.id, qr.customerId);
	}

	public static String encode(byte[] token) {
		return V1_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
	}

	/** The raw token of a {@value #V1_PREFIX} QR, or empty if it isn't one. */
	public static Optional<byte[]> decode(String qr) {
		if (qr == null || !qr.startsWith(V1_PREFIX) || qr.length() != V1_PREFIX.length() + TOKEN_CHARS) {
			return Optional.empty();
		}
		try {
			byte[] token = Base64.getUrlDecoder().decode(qr.substring(V1_PREFIX.length()));
			return token.length == TOKEN_BYTES ? Optional.of(token) : Optional.empty();
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
