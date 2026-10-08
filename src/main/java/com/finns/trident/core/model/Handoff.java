package com.finns.trident.core.model;

import com.finns.trident.core.Hashes;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * A one-time code that signs a customer in to the booking website, issued to a customer who is signed
 * in to the app. The app opens booking with the code; booking redeems it with core and starts its own
 * session. Only the code's hash is stored.
 * <p>
 * It carries the customer's display name and email only so booking can say who it is about to sign in,
 * which is why they are cleared once the code is used or revoked ({@link Customer} keeps no profile).
 */
@Entity
public class Handoff extends PanacheEntityBase {
	static final int CODE_BYTES = 32;

	/** Unpadded base64url length of {@link #CODE_BYTES}. */
	static final int CODE_CHARS = 43;

	/** How many codes a customer can have issued within {@link #RATE_WINDOW}. */
	public static final int RATE_LIMIT = 5;

	static final Duration RATE_WINDOW = Duration.ofMinutes(1);

	/** Expired rows are kept this long, so a replayed code is still told apart from an unknown one in logs. */
	static final Duration RETENTION = Duration.ofHours(1);

	@Id
	public UUID id;

	@Column(nullable = false)
	public Long customerId;

	@Column(nullable = false)
	public byte[] codeHash;

	public String keycloakSid;

	public String displayName;

	public String email;

	@Column(nullable = false)
	public Instant issuedAt;

	@Column(nullable = false)
	public Instant expiresAt;

	public Instant usedAt;

	/** A freshly issued code. {@code code} is the raw value: return it to the app, never log it. */
	public record Issued(UUID id, String code, Instant expiresAt) {
	}

	/** Who a code would sign in, for booking's confirmation page. */
	public record Peeked(String displayName, String email) {
	}

	/** Who a redeemed code signs in. */
	public record Redeemed(String sub, String sid, String displayName, String email) {
	}

	/** Empty when the customer already has {@link #RATE_LIMIT} codes issued within the last minute. */
	public static Optional<Issued> issue(Customer customer, String sid, String displayName, String email,
			Duration ttl, Instant now) {
		delete("expiresAt < ?1", now.minus(RETENTION));
		// Locks the customer's row until the transaction ends, so concurrent requests count one at a time
		// and can't all slip under the limit.
		Customer.findById(customer.id, LockModeType.PESSIMISTIC_WRITE);
		if (count("customerId = ?1 and issuedAt > ?2", customer.id, now.minus(RATE_WINDOW)) >= RATE_LIMIT) {
			return Optional.empty();
		}
		byte[] code = new byte[CODE_BYTES];
		// Not a static field: native images initialise classes at build time, which would bake the
		// seed into the image.
		new SecureRandom().nextBytes(code);

		Handoff handoff = new Handoff();
		handoff.id = UUID.randomUUID();
		handoff.customerId = customer.id;
		handoff.codeHash = Hashes.sha256(code);
		handoff.keycloakSid = sid;
		handoff.displayName = displayName;
		handoff.email = email;
		handoff.issuedAt = now;
		handoff.expiresAt = now.plus(ttl);
		handoff.persist();
		return Optional.of(new Issued(handoff.id, encode(code), handoff.expiresAt));
	}

	/**
	 * The display name ({@code ""} if the token had none) and email (null if none) of an unused, unexpired
	 * code, without using it. Booking shows both: a display name is whatever the account holder typed, so
	 * it can't tell the customer whose account a code is for on its own.
	 */
	public static Optional<Peeked> peek(String code, Instant now) {
		return decode(code)
				.flatMap(raw -> find("codeHash = ?1 and usedAt is null and expiresAt > ?2", Hashes.sha256(raw), now)
						.<Handoff>firstResultOptional())
				.map(h -> new Peeked(h.displayName == null ? "" : h.displayName, h.email));
	}

	/**
	 * Uses the code if it is unused and unexpired, and clears what it carried. One conditional update,
	 * so two requests redeeming the same code at once can't both win. Anything else (unknown, malformed,
	 * used, expired, revoked) is empty, so a caller can't tell them apart.
	 */
	public static Optional<Redeemed> redeem(String code, Instant now) {
		Optional<byte[]> raw = decode(code);
		if (raw.isEmpty()) return Optional.empty();
		byte[] hash = Hashes.sha256(raw.get());
		Optional<Handoff> found = find("codeHash = ?1 and usedAt is null and expiresAt > ?2", hash, now).firstResultOptional();
		if (found.isEmpty()) return Optional.empty();
		Handoff handoff = found.get();
		// Read first, since the conditional update clears the columns; only the update's winner returns them.
		Customer customer = Customer.findById(handoff.customerId);
		Redeemed redeemed = new Redeemed(customer.keycloakSub, handoff.keycloakSid, handoff.displayName, handoff.email);
		int updated = update("usedAt = ?1, displayName = null, email = null where id = ?2 and usedAt is null and expiresAt > ?1",
				now, handoff.id);
		return updated == 1 ? Optional.of(redeemed) : Optional.empty();
	}

	/**
	 * Clears the name and email of codes that expired unused, and deletes codes expired for longer than
	 * {@link #RETENTION}. Run every minute ({@code HandoffPurge}), so a code never keeps them for much
	 * longer than it lives.
	 */
	public static void purge(Instant now) {
		update("displayName = null, email = null where expiresAt <= ?1 and (displayName is not null or email is not null)", now);
		delete("expiresAt < ?1", now.minus(RETENTION));
	}

	/** Makes the unused codes started from this Keycloak session unusable. */
	public static void revokeSession(String sid, Instant now) {
		update("usedAt = ?1, displayName = null, email = null where keycloakSid = ?2 and usedAt is null", now, sid);
	}

	static String encode(byte[] code) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(code);
	}

	/** The raw bytes of a code, or empty if it isn't one. */
	static Optional<byte[]> decode(String code) {
		if (code == null || code.length() != CODE_CHARS) return Optional.empty();
		try {
			byte[] raw = Base64.getUrlDecoder().decode(code);
			return raw.length == CODE_BYTES ? Optional.of(raw) : Optional.empty();
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
