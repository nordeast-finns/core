package com.finns.trident.keycloak;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * The {@code finns-core} event listener. Configured with {@code url} (core's origin, https), {@code token}
 * (core's {@code FINNS_KEYCLOAK_WEBHOOK_TOKEN}) and {@code realm} (default {@code finns}), for example
 * {@code KC_SPI_EVENTS_LISTENER__FINNS_CORE__URL}. Misconfigured, it logs an error and does nothing rather
 * than stop Keycloak: core's reconciliation still copies every change, only later.
 */
public class FinnsCoreEventListenerFactory implements EventListenerProviderFactory {
	public static final String ID = "finns-core";

	private static final Logger logger = Logger.getLogger(FinnsCoreEventListenerFactory.class);

	private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30));

	private CoreNotifier notifier;

	private String realm;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public void init(Config.Scope config) {
		String url = config.get("url");
		String token = config.get("token");
		realm = config.get("realm", "finns");
		if (url == null || url.isBlank() || token == null || token.length() < 32) {
			logger.error("finns-core: url and token (32+ characters) must be set; core won't hear of changes");
			return;
		}
		URI coreUrl = URI.create(url);
		if (!CoreNotifier.isSafe(coreUrl)) {
			logger.errorf("finns-core: url must be https (http only for localhost): %s", url);
			return;
		}
		notifier = new CoreNotifier(coreUrl, token, BACKOFF);
		logger.infof("finns-core: telling %s about users of realm %s", coreUrl, realm);
	}

	@Override
	public EventListenerProvider create(KeycloakSession session) {
		return new FinnsCoreEventListener(session, notifier, realm);
	}

	@Override
	public void postInit(KeycloakSessionFactory factory) {
	}

	@Override
	public void close() {
	}
}
