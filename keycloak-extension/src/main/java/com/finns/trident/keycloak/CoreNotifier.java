package com.finns.trident.keycloak;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tells core a user changed: {@code POST <core>/api/v1/keycloak/user-changes {"userId": ...}}. Only the id:
 * core reads the user from Keycloak itself. Asynchronous, so no sign-in ever waits on core, and retried a
 * few times; core's reconciliation copies whatever is still lost.
 */
final class CoreNotifier {
	private static final Logger logger = Logger.getLogger(CoreNotifier.class);

	static final String PATH = "/api/v1/keycloak/user-changes";

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/** Hosts that may be reached over plain http, for local runs; anywhere else the token needs https. */
	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "host.docker.internal");

	private final URI endpoint;

	private final String authorization;

	/** Waits before each try: the first at once, then after these. */
	private final List<Duration> backoff;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	CoreNotifier(URI coreUrl, String token, List<Duration> backoff) {
		this.endpoint = coreUrl.resolve(PATH);
		this.authorization = "Bearer " + token;
		this.backoff = backoff;
	}

	static boolean isSafe(URI url) {
		return "https".equals(url.getScheme()) || "http".equals(url.getScheme()) && LOCAL_HOSTS.contains(url.getHost());
	}

	/** Completes when core has the notice, or once every try failed (which it logs). */
	CompletableFuture<Boolean> userChanged(String userId) {
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(TIMEOUT)
				.header("Authorization", authorization)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"userId\":" + json(userId) + "}"))
				.build();
		return attempt(request, userId, 0);
	}

	private CompletableFuture<Boolean> attempt(HttpRequest request, String userId, int tried) {
		return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((response, error) -> {
			if (error == null && response.statusCode() == 204) return CompletableFuture.completedFuture(true);
			String why = error != null ? error.getClass().getSimpleName() : "status " + response.statusCode();
			boolean retry = tried < backoff.size()
					// A refused notice won't be accepted on a retry either.
					&& (error != null || response.statusCode() >= 500 || response.statusCode() == 429);
			logger.warnf("finns-core: notice failed userId=%s reason=%s retry=%b", userId, why, retry);
			if (!retry) return CompletableFuture.completedFuture(false);
			Duration wait = backoff.get(tried);
			return CompletableFuture.supplyAsync(() -> null,
							CompletableFuture.delayedExecutor(wait.toMillis(), TimeUnit.MILLISECONDS))
					.thenCompose(ignored -> attempt(request, userId, tried + 1));
		}).thenCompose(result -> result);
	}

	/** A JSON string literal. */
	static String json(String s) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				default -> {
					if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
					else out.append(c);
				}
			}
		}
		return out.append('"').toString();
	}
}
