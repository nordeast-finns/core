package com.finns.trident.keycloak;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a stand-in for core's {@code /api/v1/keycloak/user-changes}. */
class CoreNotifierTest {
	private static final String TOKEN = "test-only-keycloak-token-not-a-secret-0";

	record Received(String path, String authorization, String contentType, String body) {
	}

	private HttpServer server;

	private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();

	/** Statuses to answer with, one per request; 204 once it runs out. */
	private final BlockingQueue<Integer> statuses = new LinkedBlockingQueue<>();

	private CoreNotifier notifier;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestHeaders().getFirst("Content-Type"),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			Integer status = statuses.poll();
			exchange.sendResponseHeaders(status == null ? 204 : status, -1);
			exchange.close();
		});
		server.start();
		URI core = URI.create("http://localhost:" + server.getAddress().getPort());
		notifier = new CoreNotifier(core, TOKEN, List.of(Duration.ofMillis(10), Duration.ofMillis(10)));
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void tellsCoreWhichUserChanged() throws Exception {
		assertTrue(notifier.userChanged("6c1f7a52-8a77-4a43-9f43-6f5e0b6f2c11").get(5, TimeUnit.SECONDS));
		Received r = received.poll(1, TimeUnit.SECONDS);
		assertEquals("/api/v1/keycloak/user-changes", r.path());
		assertEquals("Bearer " + TOKEN, r.authorization());
		assertEquals("application/json", r.contentType());
		assertEquals("{\"userId\":\"6c1f7a52-8a77-4a43-9f43-6f5e0b6f2c11\"}", r.body());
		assertNull(received.poll(100, TimeUnit.MILLISECONDS));
	}

	@Test
	void retriesWhileCoreIsUnavailable() throws Exception {
		statuses.add(503);
		statuses.add(500);
		assertTrue(notifier.userChanged("u").get(5, TimeUnit.SECONDS));
		assertEquals(3, received.size());
	}

	@Test
	void givesUpAfterItsRetries() throws Exception {
		statuses.addAll(List.of(503, 503, 503, 503));
		assertFalse(notifier.userChanged("u").get(5, TimeUnit.SECONDS));
		assertEquals(3, received.size());
	}

	@Test
	void doesNotRetryARefusedNotice() throws Exception {
		statuses.add(401);
		assertFalse(notifier.userChanged("u").get(5, TimeUnit.SECONDS));
		assertEquals(1, received.size());
	}

	@Test
	void retriesWhenCoreCantBeReached() throws Exception {
		server.stop(0);
		assertFalse(notifier.userChanged("u").get(10, TimeUnit.SECONDS));
	}

	@Test
	void escapesTheId() {
		assertEquals("\"f:ldap:a\\\"b\\\\c\\u000a\"", CoreNotifier.json("f:ldap:a\"b\\c\n"));
	}

	@Test
	void sendsTheTokenOnlyOverHttpsOrToThisMachine() {
		assertTrue(CoreNotifier.isSafe(URI.create("https://api.example.com")));
		assertTrue(CoreNotifier.isSafe(URI.create("http://localhost:8080")));
		assertTrue(CoreNotifier.isSafe(URI.create("http://host.docker.internal:8080")));
		assertFalse(CoreNotifier.isSafe(URI.create("http://api.example.com")));
	}

	@Test
	void readsTheUserFromAnAdminEventsPath() {
		assertEquals("abc", FinnsCoreEventListener.userIdOf("users/abc"));
		assertEquals("abc", FinnsCoreEventListener.userIdOf("users/abc/role-mappings/realm"));
		assertNull(FinnsCoreEventListener.userIdOf("users/"));
		assertNull(FinnsCoreEventListener.userIdOf("groups/abc"));
		assertNull(FinnsCoreEventListener.userIdOf(null));
	}
}
