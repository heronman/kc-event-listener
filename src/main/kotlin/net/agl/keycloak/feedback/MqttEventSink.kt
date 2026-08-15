package net.agl.keycloak.feedback

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.keycloak.events.Event
import org.keycloak.util.JsonSerialization

/** Publishes the full event as a JSON payload to [MqttSinkConfig.topic]. Connects eagerly at construction. */
class MqttEventSink(private val config: MqttSinkConfig) : EventSink {

    private val client = MqttClient(config.brokerUrl, config.clientId, MemoryPersistence()).apply {
        connect(
            MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                config.username?.let { userName = it }
                config.password?.let { password = it.toCharArray() }
            },
        )
    }

    override fun send(event: Event) {
        val payload = JsonSerialization.writeValueAsString(event).toByteArray(Charsets.UTF_8)
        client.publish(config.topic, MqttMessage(payload).apply { qos = config.qos })
    }

    override fun close() {
        runCatching { client.disconnect() }
        client.close()
    }
}
