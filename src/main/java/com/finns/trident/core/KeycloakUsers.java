package com.finns.trident.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reads customers' accounts from Keycloak's Admin API, as the service account of
 * {@code finns.keycloak.client-id}, which may only view users. The one source of the name and email core
 * keeps (see {@link com.finns.trident.core.model.Customer#sync}).
 */
@ApplicationScoped
public class KeycloakUsers {
	/** A Keycloak user as core copies it. Either value may be null. */
	public record User(String id, String displayName, String email) {
	}

	/** One page of {@link #page}: the customers on it, and whether it was the last. */
	public record Page(List<User> users, boolean last) {
	}

	/** Keycloak couldn't be reached, or answered in a way core doesn't understand. Nothing was learned. */
	public static class UnavailableException extends RuntimeException {
		UnavailableException(String reason) {
			super(reason);
		}
	}

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/** A cached access token is renewed this long before Keycloak says it expires. */
	private static final Duration TOKEN_MARGIN = Duration.ofSeconds(30);

	@Inject
	FinnsConfig config;

	@Inject
	ObjectMapper mapper;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	/** Guarded by {@code this}. */
	private String accessToken;

	private Instant accessTokenRenewAt = Instant.MIN;

	/** Fails startup rather than ever sending the client secret in the clear, or to a URL that isn't a realm. */
	void checkIssuer(@Observes StartupEvent event) {
		URI issuer = config.keycloak().issuer();
		if (!SafeUrls.isSafe(issuer) || !issuer.getPath().matches("(/.*)?/realms/[^/]+")) {
			throw new IllegalStateException("finns.keycloak.issuer must be a realm's https URL (http only for localhost): " + issuer);
		}
	}

	/**
	 * The user with this id; empty if Keycloak has none, or it's a client's service account, which is no
	 * customer.
	 */
	public Optional<User> user(String id) {
		HttpResponse<String> response = get("/users/" + URLEncoder.encode(id, UTF_8).replace("+", "%20"));
		if (response.statusCode() == 404) return Optional.empty();
		return customer(json(response));
	}

	/** Users {@code first} to {@code first + max - 1} of the realm, in Keycloak's order. */
	public Page page(int first, int max) {
		JsonNode list = json(get("/users?briefRepresentation=false&first=" + first + "&max=" + max));
		if (!list.isArray()) throw new UnavailableException("users is not a list");
		List<User> users = new ArrayList<>();
		for (JsonNode node : list) customer(node).ifPresent(users::add);
		return new Page(users, list.size() < max);
	}

	/**
	 * {@code displayName} is built the way Keycloak builds the {@code name} claim: the first and last name,
	 * leaving out an empty one, joined by a space.
	 */
	private static Optional<User> customer(JsonNode node) {
		String id = text(node, "id");
		if (id == null) throw new UnavailableException("user without an id");
		if (text(node, "serviceAccountClientLink") != null) return Optional.empty();
		String name = Stream.of(text(node, "firstName"), text(node, "lastName"))
				.filter(Objects::nonNull)
				.collect(Collectors.joining(" "));
		return Optional.of(new User(id, name.isEmpty() ? null : name, text(node, "email")));
	}

	/** The field's text, as Keycloak stored it; null when missing, null or empty. */
	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || !value.isTextual() || value.asText().isEmpty() ? null : value.asText();
	}

	/** GET on the realm's Admin API. 200 and 404 are answers; anything else is {@link UnavailableException}. */
	private HttpResponse<String> get(String path) {
		HttpResponse<String> response = send(adminGet(path, accessToken(false)));
		// The cached token was revoked or the realm's keys rotated: one more try with a new one.
		if (response.statusCode() == 401) response = send(adminGet(path, accessToken(true)));
		if (response.statusCode() != 200 && response.statusCode() != 404) {
			throw new UnavailableException("status " + response.statusCode());
		}
		return response;
	}

	private HttpRequest adminGet(String path, String token) {
		String issuer = config.keycloak().issuer().toString();
		int realms = issuer.lastIndexOf("/realms/");
		String base = issuer.substring(0, realms) + "/admin" + issuer.substring(realms);
		return HttpRequest.newBuilder(URI.create(base + path))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/json")
				.GET()
				.build();
	}

	/** The service account's access token, cached until shortly before it expires. */
	private synchronized String accessToken(boolean renew) {
		if (!renew && accessToken != null && Instant.now().isBefore(accessTokenRenewAt)) return accessToken;
		String form = "grant_type=client_credentials"
				+ "&client_id=" + URLEncoder.encode(config.keycloak().clientId(), UTF_8)
				+ "&client_secret=" + URLEncoder.encode(config.keycloak().clientSecret(), UTF_8);
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(config.keycloak().issuer() + "/protocol/openid-connect/token"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build());
		if (response.statusCode() != 200) throw new UnavailableException("token status " + response.statusCode());
		JsonNode body = json(response);
		String token = text(body, "access_token");
		if (token == null) throw new UnavailableException("token without access_token");
		accessToken = token;
		accessTokenRenewAt = Instant.now().plusSeconds(body.path("expires_in").asLong(0)).minus(TOKEN_MARGIN);
		return token;
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
		} catch (IOException e) {
			throw new UnavailableException(e.getClass().getSimpleName());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new UnavailableException("interrupted");
		}
	}

	private JsonNode json(HttpResponse<String> response) {
		try {
			return mapper.readTree(response.body());
		} catch (IOException e) {
			throw new UnavailableException("malformed JSON");
		}
	}
}
