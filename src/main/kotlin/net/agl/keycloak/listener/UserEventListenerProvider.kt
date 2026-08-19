package net.agl.keycloak.listener

import net.agl.keycloak.feedback.EventSinkRegistry
import org.jboss.logging.Logger
import org.keycloak.events.Event
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.admin.AdminEvent
import org.keycloak.models.KeycloakSession

/**
 * One instance is created per request by [UserEventListenerProviderFactory.create]
 * and closed at the end of that request — do not cache state across [close].
 */
class UserEventListenerProvider(
    private val session: KeycloakSession,
) : EventListenerProvider {

    companion object {
        private val log = Logger.getLogger(UserEventListenerProvider::class.java)
    }

    // Fired for user-facing events: LOGIN, LOGOUT, REGISTER, UPDATE_PROFILE, ...
    override fun onEvent(event: Event) {
        log.infof(
            "event type=%s realmId=%s clientId=%s userId=%s",
            event.type, event.realmId, event.clientId, event.userId,
        )
        EventSinkRegistry.sendAll(event)
    }

    // Fired for admin console / admin REST API events.
    // includeRepresentation indicates whether adminEvent.representation was populated
    // (controlled by the "Include representation" toggle on the realm's event config).
    override fun onEvent(adminEvent: AdminEvent, includeRepresentation: Boolean) {
        // not implemented
    }

    override fun close() {
        // not needed as the factory close() hook already has the necessary logic
    }
}
