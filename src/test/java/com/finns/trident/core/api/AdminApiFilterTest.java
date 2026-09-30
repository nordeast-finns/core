package com.finns.trident.core.api;

import com.finns.trident.core.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AdminApiFilterTest {
	@Test
	void rejectsMissingToken() {
		given().get("/api/v1/admin/staff-access/x").then().statusCode(401).body(emptyString());
	}

	@Test
	void rejectsWrongToken() {
		given().header("Authorization", "Bearer " + Fixtures.TOKEN.replace('t', 'x'))
				.get("/api/v1/admin/staff-access/x").then().statusCode(401);
	}

	@Test
	void rejectsTokenOfDifferentLength() {
		given().header("Authorization", "Bearer x").get("/api/v1/admin/staff-access/x").then().statusCode(401);
		given().header("Authorization", Fixtures.TOKEN).get("/api/v1/admin/staff-access/x").then().statusCode(401);
	}

	@Test
	void protectsUnknownAdminPathsSoTheyCantBeProbed() {
		given().get("/api/v1/admin/nothing-here").then().statusCode(401);
		given().get("/api/v1/admin").then().statusCode(401);
	}

	@Test
	void acceptsValidToken() {
		Fixtures.worker().get("/api/v1/admin/staff-access/x").then().statusCode(200);
	}

	@Test
	void leavesHealthChecksOpen() {
		given().get("/q/health/ready").then().statusCode(200);
	}

	/**
	 * The filter matches a path prefix before routing, so every spelling the router would still
	 * route to an admin endpoint must be refused too. Sent raw, so no client normalizes them first.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"//api/v1/admin/staff", "/api//v1/admin/staff", "/api/v1/admin//staff",
			"/api/./v1/admin/staff", "/x/../api/v1/admin/staff", "/api/v1/%61dmin/staff",
			"/api/v1/admin%2Fstaff", "/api/v1/admin;x/staff", "/api/v1/admin/staff;x", "/api/v1/admin/staff/",
			"/API/v1/admin/staff", "/api/v1/admin\\staff"})
	void refusesPathSpellingsThatDodgeThePrefix(String path) throws IOException {
		int status = rawStatus(path);
		assertTrue(status == 400 || status == 401 || status == 404, path + " answered " + status);
	}

	private static int rawStatus(String path) throws IOException {
		try (Socket socket = new Socket("localhost", RestAssured.port)) {
			Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
			out.write("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nX-Finns-Actor-Sub: x\r\nConnection: close\r\n\r\n");
			out.flush();
			String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
					.readLine();
			return Integer.parseInt(statusLine.split(" ")[1]);
		}
	}
}
