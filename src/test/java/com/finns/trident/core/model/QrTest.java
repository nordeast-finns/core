package com.finns.trident.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrTest {
	@Test
	void encodesAndDecodes() {
		byte[] token = new byte[Qr.TOKEN_BYTES];
		Arrays.fill(token, (byte) 0xfb);
		String qr = Qr.encode(token);
		assertTrue(qr.startsWith("FINNS1:"));
		assertEquals(Qr.V1_PREFIX.length() + Qr.TOKEN_CHARS, qr.length());
		assertArrayEquals(token, Qr.decode(qr).orElseThrow());
	}

	@Test
	void rejectsAnythingElse() {
		String body = "A".repeat(Qr.TOKEN_CHARS);
		assertTrue(Qr.decode(null).isEmpty());
		assertTrue(Qr.decode("").isEmpty());
		assertTrue(Qr.decode(body).isEmpty());
		assertTrue(Qr.decode("FINNS2:" + body).isEmpty());
		assertTrue(Qr.decode("FINNS1:" + body + "A").isEmpty());
		assertTrue(Qr.decode("FINNS1:" + body.substring(1)).isEmpty());
		assertTrue(Qr.decode("FINNS1:" + body.substring(1) + "=").isEmpty());
		assertTrue(Qr.decode("FINNS1:" + body.substring(1) + "/").isEmpty());
	}
}
