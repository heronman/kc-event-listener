package net.agl.keycloak.events

import net.agl.keycloak.config.AppConfig
import net.agl.keycloak.config.leafCount
import net.agl.keycloak.feedback.EventSinkRegistry
import org.jboss.logging.Logger
import org.keycloak.Config
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventListenerProviderFactory
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory

/**
 * Registered via META-INF/services/org.keycloak.events.EventListenerProviderFactory.
 * Enable with: spi-events-listener-user-event-listener-enabled=true in keycloak.conf,
 * then add "user-event-listener" to the realm's Events Config > Event Listeners.
 */
class UserEventListenerProviderFactory : EventListenerProviderFactory {

    companion object {
        const val PROVIDER_ID = "user-event-listener"
        private val log = Logger.getLogger(UserEventListenerProviderFactory::class.java)
    }

    override fun create(session: KeycloakSession): EventListenerProvider =
        UserEventListenerProvider(session)

    // Called once per factory instance, before any create() call. `config` exposes
    // spi-events-listener-user-event-listener-<key>=<value> entries from keycloak.conf.
    override fun init(config: Config.Scope) {
        // TODO: read configuration
    }

    // Called once, after every provider factory in the server has been init()'d.
    override fun postInit(factory: KeycloakSessionFactory) {
        // Touches AppConfig so its lazy load (and any parse warnings) happen at server
        // startup rather than on the first event. See AppConfig's kdoc for source/override order.
        log.infof(
            "AppConfig resolved %d leaf value(s) from kc-event-listener.{yml,yaml,properties} " +
                "(classpath, ./, ./config; KCEL_-prefixed env vars override all)",
            AppConfig.tree.leafCount(),
        )
        // Triggers EventSinkRegistry's lazy build so webhook/broker startup failures (bad config,
        // unreachable broker) surface in the server log now, not on the first fired event.
        EventSinkRegistry.sinks
    }

    // Called on server shutdown.
    override fun close() {
        EventSinkRegistry.closeAll()
    }

    override fun getId(): String = PROVIDER_ID
}
