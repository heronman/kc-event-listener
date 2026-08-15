package net.agl.keycloak.events

import org.jboss.logging.Logger
import org.keycloak.events.Event
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventType
import org.keycloak.events.admin.AdminEvent
import org.keycloak.models.KeycloakSession

/**
 * One instance is created per request by [SkeletonEventListenerProviderFactory.create]
 * and closed at the end of that request — do not cache state across [close].
 */
class SkeletonEventListenerProvider(
    private val session: KeycloakSession,
) : EventListenerProvider {

    companion object {
        private val log = Logger.getLogger(SkeletonEventListenerProvider::class.java)
    }

    // Fired for user-facing events: LOGIN, LOGOUT, REGISTER, UPDATE_PROFILE, ...
    override fun onEvent(event: Event) {
        log.infof(
            "event type=%s realmId=%s clientId=%s userId=%s",
            event.type, event.realmId, event.clientId, event.userId,
        )
        // TODO: implement
    }

    // Fired for admin console / admin REST API events.
    // includeRepresentation indicates whether adminEvent.representation was populated
    // (controlled by the "Include representation" toggle on the realm's event config).
    override fun onEvent(adminEvent: AdminEvent, includeRepresentation: Boolean) {
        log.infof(
            "admin event operationType=%s resourceType=%s resourcePath=%s realmId=%s",
            adminEvent.operationType, adminEvent.resourceTypeAsString, adminEvent.resourcePath, adminEvent.realmId,
        )
        // TODO: implement
    }

    override fun close() {
        // TODO: release any per-request resources (HTTP clients, connections, ...)
    }
}
