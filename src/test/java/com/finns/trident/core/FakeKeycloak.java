package com.finns.trident.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for realm {@code finns}'s token endpoint and Admin API users, at
 * {@code %test.finns.keycloak.issuer}. Started once, shared by every test; {@link #reset} between tests.
 */
public final class FakeKeycloak {
	static final int PORT = 18790;

	private static final String REALM = "/realms/finns";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static HttpServer server;

	/** By id, in the order Keycloak lists them. */
	private static final Map<String, Map<String, Object>> users = new LinkedHashMap<>();

	/** Users the listing leaves out, as when users come and go between pages. */
	private static final Set<String> unlisted = ConcurrentHashMap.newKeySet();

	/** Statuses the Admin API answers with, one per request, before answering normally. */
	private static final Queue<Integer> adminStatuses = new ConcurrentLinkedQueue<>();

	private static final AtomicInteger tokensIssued = new AtomicInteger();

	private static volatile String validToken;

	private FakeKeycloak() {
	}

	/** Starts it if it isn't running, and forgets every user. */
	public static synchronized void reset() {
		if (server == null) start();
		synchronized (users) {
			users.clear();
		}
		unlisted.clear();
		adminStatuses.clear();
		tokensIssued.set(0);
		validToken = null;
	}

	/** Adds or replaces a user; null values are left out, as Keycloak leaves them out. */
	public static void user(String id, String firstName, String lastName, String email) {
		Map<String, Object> user = new LinkedHashMap<>();
		user.put("id", id);
		user.put("username", id + "-name");
		if (firstName != null) user.put("firstName", firstName);
		if (lastName != null) user.put("lastName", lastName);
		if (email != null) user.put("email", email);
		user.put("enabled", true);
		synchronized (users) {
			users.put(id, user);
		}
	}

	/** A client's service account, which Keycloak lists among the users. */
	public static void serviceAccount(String id) {
		user(id, null, null, null);
		synchronized (users) {
			users.get(id).put("serviceAccountClientLink", "some-client");
		}
	}

	public static void delete(String id) {
		synchronized (users) {
			users.remove(id);
		}
	}

	public static void unlist(String id) {
		unlisted.add(id);
	}

	/** The next Admin API requests answer with these statuses instead. */
	public static void failAdmin(Integer... statuses) {
		adminStatuses.addAll(List.of(statuses));
	}

	/** Revokes the access token core holds, so its next Admin API request gets 401. */
	public static void revokeToken() {
		validToken = null;
	}

	public static int tokensIssued() {
		return tokensIssued.get();
	}

	private static void start() {
		try {
			server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		server.createContext(REALM + "/protocol/openid-connect/token", FakeKeycloak::token);
		server.createContext("/admin" + REALM + "/users", FakeKeycloak::users);
		server.start();
	}

	private static void token(HttpExchange exchange) throws IOException {
		Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		if (!"client_credentials".equals(form.get("grant_type")) || !"finns-core-sync".equals(form.get("client_id"))
				|| !"test-only-client-secret".equals(form.get("client_secret"))) {
			respond(exchange, 401, Map.of("error", "invalid_client"));
			return;
		}
		validToken = "token-" + tokensIssued.incrementAndGet();
		respond(exchange, 200, Map.of("access_token", validToken, "expires_in", 300, "token_type", "Bearer"));
	}

	private static void users(HttpExchange exchange) throws IOException {
		Integer status = adminStatuses.poll();
		if (status != null) {
			respond(exchange, status, Map.of());
			return;
		}
		String token = validToken;
		if (token == null || !("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
			respond(exchange, 401, Map.of());
			return;
		}
		String path = exchange.getRequestURI().getRawPath();
		String prefix = "/admin" + REALM + "/users";
		synchronized (users) {
			if (path.equals(prefix)) {
				Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
				int first = Integer.parseInt(query.getOrDefault("first", "0"));
				int max = Integer.parseInt(query.getOrDefault("max", "100"));
				List<Map<String, Object>> listed = new ArrayList<>();
				for (Map<String, Object> user : users.values()) {
					if (!unlisted.contains((String) user.get("id"))) listed.add(user);
				}
				respond(exchange, 200, listed.subList(Math.min(first, listed.size()), Math.min(first + max, listed.size())));
				return;
			}
			String id = URLDecoder.decode(path.substring(prefix.length() + 1), StandardCharsets.UTF_8);
			Map<String, Object> user = users.get(id);
			if (user == null) respond(exchange, 404, Map.of("error", "User not found"));
			else respond(exchange, 200, user);
		}
	}

	private static Map<String, String> form(String body) {
		Map<String, String> values = new HashMap<>();
		if (body == null || body.isEmpty()) return values;
		for (String pair : body.split("&")) {
			int eq = pair.indexOf('=');
			values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return values;
	}

	private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = JSON.writeValueAsBytes(body);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
