package net.agl.keycloak.feedback

import org.keycloak.events.Event
import org.keycloak.events.EventType
import org.keycloak.util.JsonSerialization

/**
 * Which extra fields to surface on top of the always-sent minimum ([FeedbackPayload]'s non-`details`
 * fields). Both default to empty — nothing here is forwarded unless configured. [includeRoot] is a
 * subset of [OPTIONAL_ROOT_FIELDS]; [includeDetails] is any key from [Event.getDetails] (open-ended,
 * Keycloak doesn't publish an exhaustive list). Both land in [FeedbackPayload.details] by name.
 */
data class PayloadConfig(
    val includeRoot: Set<String> = emptySet(),
    val includeDetails: Set<String> = emptySet(),
)

/** [Event] root fields not sent by default — see [PayloadConfig.includeRoot]. */
internal val OPTIONAL_ROOT_FIELDS = setOf("id", "realmId", "ipAddress")

/**
 * What actually goes out to a sink: [Event]'s low-cardinality/non-PII fields unconditionally, plus
 * whatever [PayloadConfig] opts into — both the optional root fields and the selected `details`
 * entries end up in [details], keyed by their original name.
 */
data class FeedbackPayload(
    val time: Long,
    val type: EventType?,
    val realmName: String?,
    val clientId: String?,
    val userId: String?,
    val sessionId: String?,
    val error: String?,
    val details: Map<String, String>,
)

internal fun feedbackPayloadJson(event: Event, config: PayloadConfig): String {
    val details = LinkedHashMap<String, String>()
    if ("id" in config.includeRoot) event.id?.let { details["id"] = it }
    if ("realmId" in config.includeRoot) event.realmId?.let { details["realmId"] = it }
    if ("ipAddress" in config.includeRoot) event.ipAddress?.let { details["ipAddress"] = it }
    event.details?.forEach { (key, value) -> if (key in config.includeDetails) details[key] = value }

    val payload = FeedbackPayload(
        time = event.time,
        type = event.type,
        realmName = event.realmName,
        clientId = event.clientId,
        userId = event.userId,
        sessionId = event.sessionId,
        error = event.error,
        details = details,
    )
    return JsonSerialization.writeValueAsString(payload)
}
