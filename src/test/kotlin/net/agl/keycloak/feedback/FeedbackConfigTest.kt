package net.agl.keycloak.feedback

import net.agl.keycloak.config.propertiesToTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackConfigTest {

    @Test
    fun `parses multiple webhooks, including entries without headers`() {
        val feedback = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].headers.X-Api-Key" to "secret-1",
                "webhook[0].headers.Authorization" to "Bearer token",
                "webhook[1].url" to "https://hook2.example.com",
            ),
        )

        val webhooks = parseWebhookConfigs(feedback)

        assertEquals(
            listOf(
                WebhookConfig(
                    "https://hook1.example.com",
                    mapOf("X-Api-Key" to "secret-1", "Authorization" to "Bearer token"),
                ),
                WebhookConfig("https://hook2.example.com", emptyMap()),
            ),
            webhooks,
        )
    }

    @Test
    fun `webhook api-key defaults its header name`() {
        val feedback = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].api-key.value" to "secret-1",
            ),
        )

        val config = parseWebhookConfigs(feedback).single().apiKey

        assertEquals(ApiKeyConfig("secret-1", "X-Api-Key"), config)
    }

    @Test
    fun `webhook api-key header name is overridable`() {
        val feedback = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].api-key.value" to "secret-1",
                "webhook[0].api-key.header" to "X-Custom-Key",
            ),
        )

        val config = parseWebhookConfigs(feedback).single().apiKey

        assertEquals(ApiKeyConfig("secret-1", "X-Custom-Key"), config)
    }

    @Test
    fun `webhook authorization token defaults to Bearer, and 'none' sends the raw token`() {
        val bearer = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].authorization.token" to "secret-2",
            ),
        )
        val raw = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].authorization.token" to "secret-2",
                "webhook[0].authorization.type" to "none",
            ),
        )

        assertEquals(AuthorizationConfig.Token("secret-2", "Bearer"), parseWebhookConfigs(bearer).single().authorization)
        assertEquals(AuthorizationConfig.Token("secret-2", null), parseWebhookConfigs(raw).single().authorization)
    }

    @Test
    fun `webhook authorization with username and password parses as Basic`() {
        val feedback = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].authorization.username" to "user",
                "webhook[0].authorization.password" to "pass",
            ),
        )

        val config = parseWebhookConfigs(feedback).single().authorization

        assertEquals(AuthorizationConfig.Basic("user", "pass"), config)
    }

    @Test
    fun `webhook hmac defaults header and algorithm`() {
        val feedback = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].hmac.secret" to "shared-secret",
            ),
        )

        val config = parseWebhookConfigs(feedback).single().hmac

        assertEquals(HmacConfig("shared-secret", "X-Signature-256", "HmacSHA256", ""), config)
    }

    @Test
    fun `webhook hmac header, algorithm and prefix are overridable`() {
        val feedback = propertiesToTree(
            mapOf(
                "webhook[0].url" to "https://hook1.example.com",
                "webhook[0].hmac.secret" to "shared-secret",
                "webhook[0].hmac.header" to "X-Hub-Signature-256",
                "webhook[0].hmac.algorithm" to "HmacSHA1",
                "webhook[0].hmac.prefix" to "sha256=",
            ),
        )

        val config = parseWebhookConfigs(feedback).single().hmac

        assertEquals(HmacConfig("shared-secret", "X-Hub-Signature-256", "HmacSHA1", "sha256="), config)
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
        val feedback = propertiesToTree(mapOf("webhook[0].headers.X-Api-Key" to "secret-only-no-url"))

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
