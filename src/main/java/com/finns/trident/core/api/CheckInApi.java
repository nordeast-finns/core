package com.finns.trident.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finns.trident.core.BusinessException;
import com.finns.trident.core.FinnsConfig;
import com.finns.trident.core.model.CheckIn;
import com.finns.trident.core.model.CheckIn.Reason;
import com.finns.trident.core.model.Customer;
import com.finns.trident.core.model.PointsTxn;
import com.finns.trident.core.model.Qr;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
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
 * request is a problem. Every scan is recorded, granted or not, and a granted one earns the customer
 * {@code finns.points.earn.check-in} points, all in one transaction.
 */
@Path(GateApiFilter.PREFIX + "/check-ins")
public class CheckInApi {
	private static final Logger logger = Logger.getLogger(CheckInApi.class);

	/** Gate ids are chosen when a gate is set up, for example {@code gym-main-entrance}. */
	static final Pattern GATE_ID = Pattern.compile("[a-z0-9-]{1,64}");

	@Inject
	FinnsConfig config;

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
			long points = config.points().earn().checkIn();
			if (points > 0) {
				PointsTxn.earn(Customer.findById(outcome.customerId()), points, PointsTxn.CHECK_IN_REASON,
						outcome.qrId().toString(), now, config.points().timeZone());
			}
			logger.infof("check_in.granted qrId=%s customerId=%s gateId=%s points=%d", outcome.qrId(),
					outcome.customerId(), scan.gateId(), points);
			return Decision.GRANTED;
		}
		logger.infof("check_in.denied reason=%s qrId=%s customerId=%s gateId=%s", outcome.denied(), outcome.qrId(),
				outcome.customerId(), scan.gateId());
		return Decision.denied(outcome.denied());
	}
}
