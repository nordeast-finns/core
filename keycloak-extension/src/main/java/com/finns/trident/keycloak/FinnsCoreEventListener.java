package com.finns.trident.keycloak;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;

import java.util.HashSet;
import java.util.Set;

/**
 * Tells core about every user event that can create, change or delete a user's name or email, and every
 * admin change to a user, once Keycloak's transaction commits (so core reads the change, and a rolled-back
 * change says nothing). Signing out isn't one: it changes no user.
 */
final class FinnsCoreEventListener implements EventListenerProvider {
	static final Set<EventType> TYPES = Set.of(
			EventType.REGISTER,
			EventType.LOGIN,
			EventType.UPDATE_PROFILE,
			EventType.UPDATE_EMAIL,
			EventType.VERIFY_EMAIL,
			EventType.DELETE_ACCOUNT,
			EventType.IDENTITY_PROVIDER_FIRST_LOGIN,
			EventType.IDENTITY_PROVIDER_LOGIN,
			EventType.IDENTITY_PROVIDER_LINK_ACCOUNT,
			EventType.FEDERATED_IDENTITY_LINK);

	private final KeycloakSession session;

	/** Null when misconfigured. */
	private final CoreNotifier notifier;

	private final String realm;

	/** Users already queued in this session's transaction, so core hears of each once. */
	private final Set<String> queued = new HashSet<>();

	FinnsCoreEventListener(KeycloakSession session, CoreNotifier notifier, String realm) {
		this.session = session;
		this.notifier = notifier;
		this.realm = realm;
	}

	@Override
	public void onEvent(Event event) {
		if (TYPES.contains(event.getType())) userChanged(event.getRealmId(), event.getUserId());
	}

	@Override
	public void onEvent(AdminEvent event, boolean includeRepresentation) {
		if (event.getResourceType() == ResourceType.USER) userChanged(event.getRealmId(), userIdOf(event.getResourcePath()));
	}

	/** {@code users/<id>}, or one of its sub-resources: the id; null for anything else. */
	static String userIdOf(String resourcePath) {
		if (resourcePath == null || !resourcePath.startsWith("users/")) return null;
		String rest = resourcePath.substring("users/".length());
		int slash = rest.indexOf('/');
		String id = slash < 0 ? rest : rest.substring(0, slash);
		return id.isEmpty() ? null : id;
	}

	private void userChanged(String realmId, String userId) {
		if (notifier == null || userId == null || !inRealm(realmId) || !queued.add(userId)) return;
		KeycloakTransactionManager transaction = session.getTransactionManager();
		if (!transaction.isActive()) {
			notifier.userChanged(userId);
			return;
		}
		transaction.enlistAfterCompletion(new AbstractKeycloakTransaction() {
			@Override
			protected void commitImpl() {
				notifier.userChanged(userId);
			}

			@Override
			protected void rollbackImpl() {
			}
		});
	}

	private boolean inRealm(String realmId) {
		if (realmId == null) return false;
		RealmModel model = session.realms().getRealm(realmId);
		return model != null && realm.equals(model.getName());
	}

	@Override
	public void close() {
	}
}
