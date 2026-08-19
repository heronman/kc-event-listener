package net.agl.keycloak.feedback

import org.keycloak.events.Event
import org.keycloak.events.EventType
import org.keycloak.util.JsonSerialization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackPayloadTest {

    private fun sampleEvent() = Event().apply {
        id = "event-1"
        time = 1_700_000_000_000
        type = EventType.LOGIN
        realmId = "realm-uuid"
        realmName = "myrealm"
        clientId = "myclient"
        userId = "user-1"
        sessionId = "session-1"
        ipAddress = "203.0.113.7"
        error = "invalid_user_credentials"
        details = mapOf("username" to "alice", "redirect_uri" to "https://app.example.com")
    }

    @Test
    fun `default config forwards only the always-sent fields, details empty`() {
        val json = feedbackPayloadJson(sampleEvent(), PayloadConfig())
        val map = JsonSerialization.mapper.readValue(json, Map::class.java)

        assertEquals(1_700_000_000_000L, (map["time"] as Number).toLong())
        assertEquals("LOGIN", map["type"])
        assertEquals("myrealm", map["realmName"])
        assertEquals("myclient", map["clientId"])
        assertEquals("user-1", map["userId"])
        assertEquals("session-1", map["sessionId"])
        assertEquals("invalid_user_credentials", map["error"])

        assertFalse(json.contains("event-1"), "id must not leak by default")
        assertFalse(json.contains("realm-uuid"), "realmId must not leak by default")
        assertFalse(json.contains("203.0.113.7"), "ipAddress must not leak by default")
        assertFalse(json.contains("alice"), "details entries must not leak by default")

        assertEquals(emptyMap<String, String>(), map["details"])
    }

    @Test
    fun `include-root fields land in details by their own name`() {
        val config = PayloadConfig(includeRoot = setOf("id", "realmId", "ipAddress"))
        val json = feedbackPayloadJson(sampleEvent(), config)
        val map = JsonSerialization.mapper.readValue(json, Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val details = map["details"] as Map<String, String>
        assertEquals("event-1", details["id"])
        assertEquals("realm-uuid", details["realmId"])
        assertEquals("203.0.113.7", details["ipAddress"])
    }

    @Test
    fun `include-details forwards only the selected Event details keys`() {
        val config = PayloadConfig(includeDetails = setOf("redirect_uri"))
        val json = feedbackPayloadJson(sampleEvent(), config)
        val map = JsonSerialization.mapper.readValue(json, Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val details = map["details"] as Map<String, String>
        assertEquals(mapOf("redirect_uri" to "https://app.example.com"), details)
        assertFalse(json.contains("alice"), "unselected details keys must not leak")
    }
}
