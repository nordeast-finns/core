package com.finns.trident.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hashing shared by the token filters and QR codes. */
public final class Hashes {
	private Hashes() {
	}

	public static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static byte[] sha256(String s) {
		return sha256(s.getBytes(StandardCharsets.UTF_8));
	}
}
