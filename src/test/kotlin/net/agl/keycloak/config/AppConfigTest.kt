package net.agl.keycloak.config

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppConfigTest {

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))

    @Test
    fun `yaml nests into a tree navigable by key and index`() {
        val yaml = """
            event-listener:
              webhook:
                url: https://example.com/hook
                retries: 3
              topics:
                - login
                - logout
        """.trimIndent()

        val tree = yamlToTree(stream(yaml))

        assertEquals("https://example.com/hook", tree["event-listener"]["webhook"]["url"].asString())
        assertEquals(3, tree["event-listener"]["webhook"]["retries"].asInt(-1))
        assertEquals(listOf("login", "logout"), tree["event-listener"]["topics"].asStringList())
        assertNull(tree["event-listener"]["does-not-exist"]["nope"].asString())
    }

    @Test
    fun `dotted and indexed properties keys expand into the same tree shape as yaml`() {
        val flat = mapOf(
            "event-listener.webhook.url" to "https://example.com/hook",
            "event-listener.webhook.retries" to "3",
            "event-listener.topics[0]" to "login",
            "event-listener.topics[1]" to "logout",
        )

        val tree = propertiesToTree(flat)

        assertEquals("https://example.com/hook", tree["event-listener"]["webhook"]["url"].asString())
        assertEquals(listOf("login", "logout"), tree["event-listener"]["topics"].asStringList())
    }

    @Test
    fun `deep merge overrides leaves recursively but replaces arrays wholesale`() {
        val base = propertiesToTree(
            mapOf(
                "event-listener.webhook.url" to "https://base.example.com",
                "event-listener.webhook.retries" to "3",
                "event-listener.topics[0]" to "login",
            ),
        )
        val override = propertiesToTree(
            mapOf(
                "event-listener.webhook.url" to "https://override.example.com",
                "event-listener.topics[0]" to "logout",
            ),
        )

        val merged = deepMerge(base, override) as ConfigNode.Obj

        // overridden leaf wins
        assertEquals("https://override.example.com", merged["event-listener"]["webhook"]["url"].asString())
        // sibling leaf not touched by the override survives
        assertEquals(3, merged["event-listener"]["webhook"]["retries"].asInt(-1))
        // array replaced wholesale by the override, not merged element-by-element
        assertEquals(listOf("logout"), merged["event-listener"]["topics"].asStringList())
    }

    @Test
    fun `env var name is KCEL_-prefixed, relaxed binding collapses dots and dashes to underscore`() {
        assertEquals("KCEL_EVENT_LISTENER_WEBHOOK_URL", toEnvVarName("event-listener.webhook.url"))
        assertEquals("KCEL_EVENT_LISTENER_WEBHOOK_URL", toEnvVarName("event-listener.webhook-url"))
    }

    @Test
    fun `an empty or fully commented-out yaml file parses to an empty tree, not an error`() {
        val yaml = """
            # nothing active in this file, like the shipped kc-event-listener.yml example
            # feedback:
            #   webhook:
            #     - url: https://example.com/hook
        """.trimIndent()

        val tree = yamlToTree(stream(yaml))

        assertEquals(ConfigNode.Obj(emptyMap()), tree)
    }
}
