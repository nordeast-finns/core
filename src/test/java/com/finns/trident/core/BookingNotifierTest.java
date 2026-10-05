package com.finns.trident.core;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Against a stand-in for the booking website's {@code /auth/revoke}. */
@QuarkusTest
@TestProfile(BookingNotifierTest.Profile.class)
class BookingNotifierTest {
	static final int PORT = 18789;

	public static class Profile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.of("finns.booking.url", "http://localhost:" + PORT);
		}
	}

	record Received(String method, String path, String authorization, String body) {
	}

	static HttpServer server;
	static final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
	/** Statuses to answer with, one per request; 200 once it runs out. */
	static final BlockingQueue<Integer> statuses = new LinkedBlockingQueue<>();

	@Inject
	BookingNotifier notifier;

	@BeforeAll
	static void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
		server.createContext("/", exchange -> {
			received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Authorization"),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			Integer status = statuses.poll();
			exchange.sendResponseHeaders(status == null ? 200 : status, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stop() {
		server.stop(0);
	}

	@BeforeEach
	void clear() {
		received.clear();
		statuses.clear();
	}

	@Test
	void tellsBookingWhichSessionEnded() throws InterruptedException {
		notifier.sessionEnded("kc-session-1");

		Received r = received.poll(5, TimeUnit.SECONDS);
		assertNotNull(r);
		assertEquals("POST", r.method());
		assertEquals("/auth/revoke", r.path());
		assertEquals("Bearer test-only-revoke-token-not-a-secret-0", r.authorization());
		assertEquals("{\"sid\":\"kc-session-1\"}", r.body());
		assertNull(received.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	void escapesTheSessionIdInTheBody() throws InterruptedException {
		notifier.sessionEnded("a\"b\\c");
		assertEquals("{\"sid\":\"a\\\"b\\\\c\"}", received.poll(5, TimeUnit.SECONDS).body());
	}

	@Test
	void triesOnceMoreWhenBookingFails() throws InterruptedException {
		statuses.add(500);
		notifier.sessionEnded("s");

		assertNotNull(received.poll(5, TimeUnit.SECONDS));
		assertNotNull(received.poll(5, TimeUnit.SECONDS));
		assertNull(received.poll(500, TimeUnit.MILLISECONDS));
	}

	@Test
	void givesUpAfterTheRetry() throws InterruptedException {
		statuses.add(500);
		statuses.add(500);
		statuses.add(500);
		notifier.sessionEnded("s");

		assertNotNull(received.poll(5, TimeUnit.SECONDS));
		assertNotNull(received.poll(5, TimeUnit.SECONDS));
		assertNull(received.poll(500, TimeUnit.MILLISECONDS));
	}
}
