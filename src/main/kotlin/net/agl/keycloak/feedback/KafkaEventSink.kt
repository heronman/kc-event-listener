package net.agl.keycloak.feedback

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringSerializer
import org.jboss.logging.Logger
import org.keycloak.events.Event
import java.io.File
import java.util.Properties

/** Publishes [FeedbackPayload] (see [PayloadConfig]) as a JSON string value to [KafkaSinkConfig.topic], keyed by userId. */
class KafkaEventSink(private val config: KafkaSinkConfig, private val payloadConfig: PayloadConfig) : EventSink {

    companion object {
        private val log = Logger.getLogger(KafkaEventSink::class.java)
    }

    private val producer: KafkaProducer<String, String> = KafkaProducer(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            config.clientId?.let { put(ProducerConfig.CLIENT_ID_CONFIG, it) }
            config.acks?.let { put(ProducerConfig.ACKS_CONFIG, it) }
            if (config.tls.enabled) {
                put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
                if (config.tls.trustedCertificates.isNotEmpty()) {
                    // PEM truststore support (KIP-651) — pins trust to exactly these certs, no JVM default CAs.
                    put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM")
                    put(
                        SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG,
                        config.tls.trustedCertificates.joinToString("\n") { File(it).readText() },
                    )
                }
            } else {
                log.warnf("Kafka sink %s connects without TLS — events are transmitted in plaintext", config.bootstrapServers)
            }
        },
    )

    override fun send(event: Event) {
        val body = feedbackPayloadJson(event, payloadConfig)
        // KafkaProducer connects lazily, so a bad bootstrap-servers value won't fail until here.
        producer.send(ProducerRecord(config.topic, event.userId, body)) { _, exception ->
            if (exception != null) {
                log.errorf(exception, "Failed to publish event type=%s to Kafka topic=%s", event.type, config.topic)
            }
        }
    }

    override fun close() {
        producer.close()
    }
}
