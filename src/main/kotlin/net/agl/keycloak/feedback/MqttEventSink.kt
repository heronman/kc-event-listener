package net.agl.keycloak.feedback

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.jboss.logging.Logger
import org.keycloak.events.Event

/**
 * Publishes [FeedbackPayload] (see [PayloadConfig]) as a JSON payload to [MqttSinkConfig.topic].
 * Connects eagerly at construction.
 *
 * Unlike Kafka/AMQP, MQTT's transport (plaintext vs TLS) is fixed by the `ssl://`/`wss://` scheme
 * in [MqttSinkConfig.brokerUrl] itself — [TlsConfig.enabled] can't override that, it only gates
 * the plaintext warning. [TlsConfig.trustedCertificates] applies whenever the URL is already
 * ssl(s)://, regardless of [TlsConfig.enabled].
 */
class MqttEventSink(private val config: MqttSinkConfig, private val payloadConfig: PayloadConfig) : EventSink {

    companion object {
        private val log = Logger.getLogger(MqttEventSink::class.java)
    }

    init {
        val encrypted = config.brokerUrl.startsWith("ssl://", ignoreCase = true) ||
            config.brokerUrl.startsWith("wss://", ignoreCase = true)
        if (!encrypted && config.tls.enabled) {
            log.warnf(
                "MQTT sink %s connects without TLS (broker-url scheme is not ssl:// or wss://) — " +
                    "events are transmitted in plaintext",
                config.brokerUrl,
            )
        }
    }

    private val client = MqttClient(config.brokerUrl, config.clientId, MemoryPersistence()).apply {
        connect(
            MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                config.username?.let { userName = it }
                config.password?.let { password = it.toCharArray() }
                if (config.tls.trustedCertificates.isNotEmpty()) {
                    socketFactory = buildSslContext(config.tls.trustedCertificates).socketFactory
                }
            },
        )
    }

    override fun send(event: Event) {
        val payload = feedbackPayloadJson(event, payloadConfig).toByteArray(Charsets.UTF_8)
        client.publish(config.topic, MqttMessage(payload).apply { qos = config.qos })
    }

    override fun close() {
        runCatching { client.disconnect() }
        client.close()
    }
}
