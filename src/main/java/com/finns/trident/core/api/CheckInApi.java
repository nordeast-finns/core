package com.finns.trident.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finns.trident.core.BusinessException;
import com.finns.trident.core.model.CheckIn;
import com.finns.trident.core.model.CheckIn.Reason;
import com.finns.trident.core.model.Qr;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.finns.trident.core.ErrorCode.MALFORMED;

/**
 * Decides a gate's scan. A denial is a normal outcome, answered 200 with a reason; only a malformed
 * request is a problem. Every scan is recorded, granted or not.
 */
@Path(GateApiFilter.PREFIX + "/check-ins")
public class CheckInApi {
	private static final Logger logger = Logger.getLogger(CheckInApi.class);

	/** Gate ids are chosen when a gate is set up, for example {@code gym-main-entrance}. */
	static final Pattern GATE_ID = Pattern.compile("[a-z0-9-]{1,64}");

	/** {@code qr} is the scanned text, verbatim. */
	@RegisterForReflection
	public record Scan(String gateId, String qr) {
	}

	/** {@code result} is {@code granted} or {@code denied}; {@code reason} is set when denied. */
	@RegisterForReflection
	@JsonInclude(NON_NULL)
	public record Decision(String result, String reason) {
		static final Decision GRANTED = new Decision("granted", null);

		static Decision denied(Reason reason) {
			return new Decision("denied", reason.name().toLowerCase(Locale.ROOT));
		}
	}

	@POST
	@Transactional
	public Decision checkIn(Scan scan) {
		if (scan == null || scan.gateId() == null || !GATE_ID.matcher(scan.gateId()).matches() || scan.qr() == null) {
			throw new BusinessException(MALFORMED);
		}
		Instant now = Instant.now();

		Optional<byte[]> token = Qr.decode(scan.qr());
		Qr.Outcome outcome = token.isPresent()
				? Qr.consume(token.get(), scan.gateId(), now)
				: new Qr.Outcome(Reason.MALFORMED, null, null);
		CheckIn.recordOnline(outcome, scan.gateId(), now);

		if (outcome.granted()) {
			logger.infof("check_in.granted qrId=%s customerId=%s gateId=%s", outcome.qrId(), outcome.customerId(), scan.gateId());
			return Decision.GRANTED;
		}
		logger.infof("check_in.denied reason=%s qrId=%s customerId=%s gateId=%s", outcome.denied(), outcome.qrId(),
				outcome.customerId(), scan.gateId());
		return Decision.denied(outcome.denied());
	}
}
