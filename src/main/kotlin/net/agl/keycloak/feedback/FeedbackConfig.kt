package net.agl.keycloak.feedback

import net.agl.keycloak.config.*

/**
 * Config shape read from `feedback` in [net.agl.keycloak.config.AppConfig] (any number of entries
 * per type, including several of the same type):
 *
 * ```yaml
 * feedback:
 *   payload:                          # optional; shapes what every sink actually sends (see FeedbackPayload)
 *     include-root: [realmId]         # optional, default []; subset of id, realmId, ipAddress
 *     include-details: [redirect_uri] # optional, default []; forwarded Event.details keys
 *   webhook:
 *     - url: https://hook1.example.com/kc-events
 *       api-key:                       # optional
 *         value: secret-1
 *         header: X-Api-Key            # optional, defaults to X-Api-Key
 *       authorization:                 # optional, sent as the Authorization header
 *         token: secret-2
 *         type: Bearer                 # optional, defaults to Bearer; "none" -> raw "Authorization: <token>"
 *         # or, for Basic auth instead of a token:
 *         # username: user
 *         # password: pass
 *       hmac:                          # optional, signs the request body
 *         secret: shared-secret
 *         header: X-Signature-256      # optional, defaults to X-Signature-256
 *         algorithm: HmacSHA256        # optional, defaults to HmacSHA256
 *         prefix: "sha256="            # optional, defaults to "" (GitHub-style webhooks use "sha256=")
 *       headers:                       # optional, sent as-is with every request; catch-all for anything else
 *         X-Custom-Header: value
 *   broker:
 *     kafka:
 *       - bootstrap-servers: localhost:9092
 *         topic: keycloak-events
 *         client-id: kc-event-listener # optional
 *         acks: all                    # optional
 *         tls:                         # optional, see TlsConfig; TLS is on by default for every broker
 *           enabled: true               # optional, defaults to true; false silences the plaintext warning
 *           trusted-certificates:       # optional, PEM file paths; pins trust to these instead of the JVM default
 *             - /etc/keycloak/certs/broker-ca.pem
 *     mqtt:
 *       - broker-url: ssl://localhost:8883  # ssl:// (or wss://) is what actually enables TLS on the wire for MQTT
 *         topic: keycloak/events
 *         client-id: kc-event-listener # optional, defaults to "kc-event-listener-<index>"
 *         username: mqttuser           # optional
 *         password: mqttpass           # optional
 *         qos: 1                       # optional, defaults to 1
 *         tls:                         # optional; trusted-certificates applies whenever broker-url is ssl(s)://
 *           trusted-certificates:
 *             - /etc/keycloak/certs/broker-ca.pem
 *     amqp:
 *       - host: localhost
 *         port: 5671                   # optional, defaults to 5671 with tls enabled (the default), else 5672
 *         username: guest              # optional
 *         password: guest              # optional
 *         virtual-host: /              # optional, defaults to "/"
 *         exchange: keycloak.events
 *         routing-key: event
 *         tls:                         # optional, see TlsConfig
 *           trusted-certificates:
 *             - /etc/keycloak/certs/broker-ca.pem
 * ```
 *
 * Every leaf here also picks up `KCEL_`-prefixed env var overrides and ./ , ./config file
 * overrides the same way the rest of AppConfig does — nothing feedback-specific about that part.
 */
data class WebhookConfig(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val apiKey: ApiKeyConfig? = null,
    val authorization: AuthorizationConfig? = null,
    val hmac: HmacConfig? = null,
)

data class ApiKeyConfig(
    val value: String,
    val header: String = "X-Api-Key",
)

sealed interface AuthorizationConfig {
    data class Basic(val username: String, val password: String) : AuthorizationConfig

    /** [type] is the Authorization scheme prefix (e.g. "Bearer"); null sends the raw token with no scheme. */
    data class Token(val token: String, val type: String?) : AuthorizationConfig
}

data class HmacConfig(
    val secret: String,
    val header: String = "X-Signature-256",
    val algorithm: String = "HmacSHA256",
    val prefix: String = "",
)

data class KafkaSinkConfig(
    val bootstrapServers: String,
    val topic: String,
    val clientId: String?,
    val acks: String?,
    val tls: TlsConfig = TlsConfig(),
)

data class MqttSinkConfig(
    val brokerUrl: String,
    val topic: String,
    val clientId: String,
    val username: String?,
    val password: String?,
    val qos: Int,
    val tls: TlsConfig = TlsConfig(),
)

data class AmqpSinkConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val virtualHost: String,
    val exchange: String,
    val routingKey: String,
    val tls: TlsConfig = TlsConfig(),
)

internal fun parseWebhookConfigs(feedback: ConfigNode?): List<WebhookConfig> =
    feedback["webhook"].asObjList().mapIndexed { i, cfg ->
        val context = "feedback.webhook[$i]"
        WebhookConfig(
            url = cfg.requireString("url", context),
            headers = cfg["headers"].asStringMap(),
            apiKey = parseApiKeyConfig(cfg["api-key"], "$context.api-key"),
            authorization = parseAuthorizationConfig(cfg["authorization"], "$context.authorization"),
            hmac = parseHmacConfig(cfg["hmac"], "$context.hmac"),
        )
    }

private fun parseApiKeyConfig(node: ConfigNode?, context: String): ApiKeyConfig? {
    if (node == null) return null
    return ApiKeyConfig(
        value = node.requireString("value", context),
        header = node["header"].asString() ?: "X-Api-Key",
    )
}

private fun parseAuthorizationConfig(node: ConfigNode?, context: String): AuthorizationConfig? {
    if (node == null) return null
    val username = node["username"].asString()
    val password = node["password"].asString()
    if (username != null || password != null) {
        return AuthorizationConfig.Basic(
            username = username ?: throw IllegalArgumentException("$context: missing required 'username'"),
            password = password ?: throw IllegalArgumentException("$context: missing required 'password'"),
        )
    }
    val token = node.requireString("token", context)
    val type = node["type"].asString() ?: "Bearer"
    return AuthorizationConfig.Token(token = token, type = if (type.equals("none", ignoreCase = true)) null else type)
}

private fun parseHmacConfig(node: ConfigNode?, context: String): HmacConfig? {
    if (node == null) return null
    return HmacConfig(
        secret = node.requireString("secret", context),
        header = node["header"].asString() ?: "X-Signature-256",
        algorithm = node["algorithm"].asString() ?: "HmacSHA256",
        prefix = node["prefix"].asString() ?: "",
    )
}

internal fun parseKafkaConfigs(feedback: ConfigNode?): List<KafkaSinkConfig> =
    feedback["broker"]["kafka"].asObjList().mapIndexed { i, cfg ->
        val context = "feedback.broker.kafka[$i]"
        KafkaSinkConfig(
            bootstrapServers = cfg.requireString("bootstrap-servers", context),
            topic = cfg.requireString("topic", context),
            clientId = cfg["client-id"].asString(),
            acks = cfg["acks"].asString(),
            tls = parseTlsConfig(cfg["tls"]),
        )
    }

internal fun parseMqttConfigs(feedback: ConfigNode?): List<MqttSinkConfig> =
    feedback["broker"]["mqtt"].asObjList().mapIndexed { i, cfg ->
        val context = "feedback.broker.mqtt[$i]"
        MqttSinkConfig(
            brokerUrl = cfg.requireString("broker-url", context),
            topic = cfg.requireString("topic", context),
            clientId = cfg["client-id"].asString() ?: "kc-event-listener-$i",
            username = cfg["username"].asString(),
            password = cfg["password"].asString(),
            qos = cfg["qos"].asInt(1),
            tls = parseTlsConfig(cfg["tls"]),
        )
    }

internal fun parseAmqpConfigs(feedback: ConfigNode?): List<AmqpSinkConfig> =
    feedback["broker"]["amqp"].asObjList().mapIndexed { i, cfg ->
        val context = "feedback.broker.amqp[$i]"
        val tls = parseTlsConfig(cfg["tls"])
        AmqpSinkConfig(
            host = cfg.requireString("host", context),
            port = cfg["port"].asInt(if (tls.enabled) 5671 else 5672),
            username = cfg["username"].asString(),
            password = cfg["password"].asString(),
            virtualHost = cfg["virtual-host"].asString() ?: "/",
            exchange = cfg.requireString("exchange", context),
            routingKey = cfg.requireString("routing-key", context),
            tls = tls,
        )
    }

private fun parseTlsConfig(node: ConfigNode?): TlsConfig =
    TlsConfig(
        enabled = node["enabled"].asBoolean(true),
        trustedCertificates = node["trusted-certificates"].asStringList(),
    )

internal fun parsePayloadConfig(feedback: ConfigNode?): PayloadConfig {
    val node = feedback["payload"]
    val includeRoot = node["include-root"].asStringList().toSet()
    val unknown = includeRoot - OPTIONAL_ROOT_FIELDS
    require(unknown.isEmpty()) {
        "feedback.payload.include-root: unknown field(s) $unknown, expected a subset of $OPTIONAL_ROOT_FIELDS"
    }
    return PayloadConfig(
        includeRoot = includeRoot,
        includeDetails = node["include-details"].asStringList().toSet(),
    )
}

private fun ConfigNode?.requireString(key: String, context: String): String =
    this[key].asString() ?: throw IllegalArgumentException("$context: missing required '$key'")
