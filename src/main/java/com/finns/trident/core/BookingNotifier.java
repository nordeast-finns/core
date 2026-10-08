package com.finns.trident.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tells the booking website that a customer signed out of the app, so it signs them out too, everywhere.
 * Best effort: if booking can't be reached after one retry, those sessions end on their own, within
 * booking's session lifetime.
 */
@ApplicationScoped
public class BookingNotifier {
	private static final Logger logger = Logger.getLogger(BookingNotifier.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Inject
	FinnsConfig config;

	@Inject
	ObjectMapper mapper;

	/** Hosts that may be reached over plain http, for local runs; anywhere else the revoke token needs https. */
	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	/** Fails startup rather than ever sending the revoke token in the clear. */
	void checkUrl(@Observes StartupEvent event) {
		URI url = config.booking().url();
		if (!isSafe(url)) throw new IllegalStateException("finns.booking.url must be https (http only for localhost): " + url);
	}

	static boolean isSafe(URI url) {
		return "https".equals(url.getScheme()) || "http".equals(url.getScheme()) && LOCAL_HOSTS.contains(url.getHost());
	}

	/**
	 * Asynchronous, so the app's sign-out never waits on booking. {@code sid} is the app's Keycloak
	 * session, null if the token had none. Neither is ever logged.
	 */
	public void signedOut(String sub, String sid) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("sub", sub);
		if (sid != null) body.put("sid", sid);
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(config.booking().url().resolve("/auth/revoke"))
					.timeout(TIMEOUT)
					.header("Authorization", "Bearer " + config.booking().revokeToken())
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
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
