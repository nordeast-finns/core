package com.finns.trident.core;

import java.net.URI;
import java.util.Set;

/** Where core may send a secret: https anywhere, plain http only to this machine, for local runs. */
public final class SafeUrls {
	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

	private SafeUrls() {
	}

	public static boolean isSafe(URI url) {
		return "https".equals(url.getScheme()) || "http".equals(url.getScheme()) && LOCAL_HOSTS.contains(url.getHost());
	}
}
