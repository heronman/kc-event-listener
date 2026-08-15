package net.agl.keycloak.events

import net.agl.keycloak.config.AppConfig
import net.agl.keycloak.config.leafCount
import org.jboss.logging.Logger
import org.keycloak.Config
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventListenerProviderFactory
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory

/**
 * Registered via META-INF/services/org.keycloak.events.EventListenerProviderFactory.
 * Enable with: spi-events-listener-skeleton-event-listener-enabled=true in keycloak.conf,
 * then add "skeleton-event-listener" to the realm's Events Config > Event Listeners.
 */
class SkeletonEventListenerProviderFactory : EventListenerProviderFactory {

    companion object {
        const val PROVIDER_ID = "skeleton-event-listener"
        private val log = Logger.getLogger(SkeletonEventListenerProviderFactory::class.java)
    }

    override fun create(session: KeycloakSession): EventListenerProvider =
        SkeletonEventListenerProvider(session)

    // Called once per factory instance, before any create() call. `config` exposes
    // spi-events-listener-skeleton-event-listener-<key>=<value> entries from keycloak.conf.
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
    }

    // Called on server shutdown.
    override fun close() {
        // TODO: release factory-wide resources
    }

    override fun getId(): String = PROVIDER_ID
}
