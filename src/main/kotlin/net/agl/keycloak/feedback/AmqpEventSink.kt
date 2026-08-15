package net.agl.keycloak.feedback

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.keycloak.events.Event
import org.keycloak.util.JsonSerialization

/** Publishes the full event as a JSON message to [AmqpSinkConfig.exchange]/[AmqpSinkConfig.routingKey]. */
class AmqpEventSink(private val config: AmqpSinkConfig) : EventSink {

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
        connection = factory.newConnection("kc-event-listener")
        channel = connection.createChannel()
    }

    override fun send(event: Event) {
        val body = JsonSerialization.writeValueAsString(event).toByteArray(Charsets.UTF_8)
        channel.basicPublish(config.exchange, config.routingKey, null, body)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }
}
