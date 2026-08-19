package net.agl.keycloak.feedback

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.jboss.logging.Logger
import org.keycloak.events.Event

/** Publishes [FeedbackPayload] (see [PayloadConfig]) as a JSON message to [AmqpSinkConfig.exchange]/[AmqpSinkConfig.routingKey]. */
class AmqpEventSink(private val config: AmqpSinkConfig, private val payloadConfig: PayloadConfig) : EventSink {

    companion object {
        private val log = Logger.getLogger(AmqpEventSink::class.java)
    }

    private val connection: Connection
    private val channel: Channel

    init {
        val factory = ConnectionFactory().apply {
            host = config.host
            port = config.port
            config.username?.let { username = it }
            config.password?.let { password = it }
            virtualHost = config.virtualHost
        }
        if (config.tls.enabled) {
            // Pins trust to exactly these certs (no JVM default CAs) when given, else JVM defaults.
            if (config.tls.trustedCertificates.isNotEmpty()) {
                factory.useSslProtocol(buildSslContext(config.tls.trustedCertificates))
            } else {
                factory.useSslProtocol()
            }
        } else {
            log.warnf("AMQP sink %s:%d connects without TLS — events are transmitted in plaintext", config.host, config.port)
        }
        connection = factory.newConnection("kc-event-listener")
        channel = connection.createChannel()
    }

    override fun send(event: Event) {
        val body = feedbackPayloadJson(event, payloadConfig).toByteArray(Charsets.UTF_8)
        channel.basicPublish(config.exchange, config.routingKey, null, body)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }
}
