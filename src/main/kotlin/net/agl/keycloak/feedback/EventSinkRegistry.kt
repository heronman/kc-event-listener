package net.agl.keycloak.feedback

import net.agl.keycloak.config.AppConfig
import net.agl.keycloak.config.get
import org.jboss.logging.Logger
import org.keycloak.events.Event

/**
 * One [EventSink] per entry under `feedback.webhook` / `feedback.broker.{kafka,mqtt,amqp}` in
 * [AppConfig] — see [WebhookConfig]'s kdoc for the exact YAML/properties shape. Multiple entries
 * of the same type are allowed (e.g. two webhooks, or a Kafka producer to two different
 * clusters); a sink that fails to start (bad config, unreachable broker) is logged and skipped,
 * it does not prevent the others from starting.
 *
 * Built once, lazily, and shared across every [net.agl.keycloak.events.UserEventListenerProvider]
 * instance (i.e. every request) for the lifetime of this classloader — call [sinks] once at
 * server startup (see the factory's `postInit`) so connection failures surface then, not on the
 * first event, and call [closeAll] on shutdown (the factory's `close`).
 */
object EventSinkRegistry {

    private val log = Logger.getLogger(EventSinkRegistry::class.java)

    val sinks: List<EventSink> by lazy { build() }

    fun sendAll(event: Event) {
        for (sink in sinks) {
            try {
                sink.send(event)
            } catch (e: Exception) {
                log.errorf(e, "%s failed to handle event type=%s", sink::class.simpleName, event.type)
            }
        }
    }

    fun closeAll() {
        for (sink in sinks) {
            runCatching { sink.close() }.onFailure { log.warnf(it, "Failed to close %s", sink::class.simpleName) }
        }
    }

    private fun build(): List<EventSink> {
        val feedback = AppConfig.tree["feedback"]
        val sinks = mutableListOf<EventSink>()

        for (cfg in parseWebhookConfigs(feedback)) {
            start("webhook ${cfg.url}") { WebhookEventSink(cfg) }?.let(sinks::add)
        }
        for (cfg in parseKafkaConfigs(feedback)) {
            start("kafka ${cfg.bootstrapServers} topic=${cfg.topic}") { KafkaEventSink(cfg) }?.let(sinks::add)
        }
        for (cfg in parseMqttConfigs(feedback)) {
            start("mqtt ${cfg.brokerUrl} topic=${cfg.topic}") { MqttEventSink(cfg) }?.let(sinks::add)
        }
        for (cfg in parseAmqpConfigs(feedback)) {
            start("amqp ${cfg.host}:${cfg.port} exchange=${cfg.exchange}") { AmqpEventSink(cfg) }?.let(sinks::add)
        }

        log.infof("EventSinkRegistry started %d feedback sink(s)", sinks.size)
        return sinks
    }

    private fun start(description: String, factory: () -> EventSink): EventSink? = try {
        factory().also { log.infof("Started feedback sink: %s", description) }
    } catch (e: Exception) {
        log.errorf(e, "Failed to start feedback sink %s, skipping it", description)
        null
    }
}
