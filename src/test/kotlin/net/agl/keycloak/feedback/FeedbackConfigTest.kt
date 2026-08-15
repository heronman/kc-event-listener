package net.agl.keycloak.feedback

import net.agl.keycloak.config.propertiesToTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackConfigTest {

    @Test
    fun `parses multiple webhooks, including entries without an api key`() {
        val feedback = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].api-key" to "secret-1",
                "webhook[1].url" to "https://hook2.example.com",
            ),
        )

        val webhooks = parseWebhookConfigs(feedback)

        assertEquals(
            listOf(
                WebhookConfig("https://hook1.example.com", "secret-1"),
                WebhookConfig("https://hook2.example.com", null),
            ),
            webhooks,
        )
    }

    @Test
    fun `parses multiple kafka entries with optional fields defaulted`() {
        val feedback = propertiesToTree(
            mapOf(
                "broker.kafka[0].bootstrap-servers" to "localhost:9092",
                "broker.kafka[0].topic" to "keycloak-events",
                "broker.kafka[1].bootstrap-servers" to "other:9092",
                "broker.kafka[1].topic" to "mirror",
                "broker.kafka[1].client-id" to "kc-event-listener",
                "broker.kafka[1].acks" to "all",
            ),
        )

        val configs = parseKafkaConfigs(feedback)

        assertEquals(2, configs.size)
        assertEquals(KafkaSinkConfig("localhost:9092", "keycloak-events", null, null), configs[0])
        assertEquals(KafkaSinkConfig("other:9092", "mirror", "kc-event-listener", "all"), configs[1])
    }

    @Test
    fun `mqtt entry falls back to an indexed client id and default qos when unset`() {
        val feedback = propertiesToTree(
            mapOf(
                "broker.mqtt[0].broker-url" to "tcp://localhost:1883",
                "broker.mqtt[0].topic" to "keycloak/events",
            ),
        )

        val config = parseMqttConfigs(feedback).single()

        assertEquals("kc-event-listener-0", config.clientId)
        assertEquals(1, config.qos)
    }

    @Test
    fun `amqp entry defaults port and virtual host when unset`() {
        val feedback = propertiesToTree(
            mapOf(
                "broker.amqp[0].host" to "localhost",
                "broker.amqp[0].exchange" to "keycloak.events",
                "broker.amqp[0].routing-key" to "event",
            ),
        )

        val config = parseAmqpConfigs(feedback).single()

        assertEquals(5672, config.port)
        assertEquals("/", config.virtualHost)
    }

    @Test
    fun `missing required key throws with a path that pinpoints the offending entry`() {
        val feedback = propertiesToTree(mapOf("webhook[0].api-key" to "secret-only-no-url"))

        val e = kotlin.test.assertFailsWith<IllegalArgumentException> { parseWebhookConfigs(feedback) }

        assertTrue(e.message!!.contains("feedback.webhook[0]"))
        assertTrue(e.message!!.contains("url"))
    }

    @Test
    fun `absent feedback section yields no sinks of any kind`() {
        val feedback = propertiesToTree(emptyMap())

        assertEquals(emptyList(), parseWebhookConfigs(feedback))
        assertEquals(emptyList(), parseKafkaConfigs(feedback))
        assertEquals(emptyList(), parseMqttConfigs(feedback))
        assertEquals(emptyList(), parseAmqpConfigs(feedback))
    }
}
