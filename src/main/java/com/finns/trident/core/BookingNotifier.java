package com.finns.trident.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Tells the booking website that a customer signed out of the app, so it ends the sessions that
 * customer's handoffs started. Best effort: if booking can't be reached after one retry, those
 * sessions end on their own, within booking's session lifetime.
 */
@ApplicationScoped
public class BookingNotifier {
	private static final Logger logger = Logger.getLogger(BookingNotifier.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Inject
	FinnsConfig config;

	@Inject
	ObjectMapper mapper;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	/** Asynchronous, so the app's sign-out never waits on booking. {@code sid} is never logged. */
	public void sessionEnded(String sid) {
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(config.booking().url().resolve("/auth/revoke"))
					.timeout(TIMEOUT)
					.header("Authorization", "Bearer " + config.booking().revokeToken())
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("sid", sid))))
					.build();
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
		send(request, true);
	}

	private void send(HttpRequest request, boolean retry) {
		client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
			if (error == null && response.statusCode() == 200) return;
			String why = error != null ? error.getClass().getSimpleName() : "status " + response.statusCode();
			logger.warnf("booking.revoke_failed reason=%s retry=%b", why, retry);
			if (retry) send(request, false);
		});
	}
}
