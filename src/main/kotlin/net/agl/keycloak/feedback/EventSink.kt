package net.agl.keycloak.feedback

import org.keycloak.events.Event

/**
 * Common contract for every feedback transport (webhook, Kafka, MQTT, AMQP, ...) — one instance
 * per configured entry, shared across all requests for the lifetime of this classloader.
 * Implementations own a live connection/client and must release it in [close].
 */
interface EventSink : AutoCloseable {
    fun send(event: Event)
}
